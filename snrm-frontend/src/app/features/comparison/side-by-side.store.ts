import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import {
  WireLink,
  WireNode,
  WireTopologicalResponse,
  normaliseLinks,
  normaliseMetrics,
  normaliseNodes,
} from '../../core/api-nulls';
import { ApiService } from '../../core/api.service';
import {
  Id,
  MetricCode,
  MetricResult,
  MetricScope,
  Network,
  NetworkLink,
  NetworkNode,
  Project,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { ProjectsStore } from '../projects/projects.store';
import { ElementKey, sameKey } from './element-matching';
import {
  MetricOption,
  MetricVisibilityControls,
  compareMetricCodes,
  metricOptions,
  metricVisibilityControls,
} from './metric-visibility';
import {
  CollapseAllControl,
  PaneGrid,
  PaneSelection,
  collapseAllControl,
  paneGrid,
  suitesExpandedByDefault,
} from './pane-grid';

/** One node's criticality within its own pane, ready to render (`NODE_CRITICALITY`). */
export interface PaneCriticality {
  readonly nodeId: Id;
  readonly name: string;
  readonly value: number;
  /** `value` against the largest in *this* network - the bar width, not the metric. */
  readonly share: number;
}

/** Everything one pane of the side-by-side window draws (FR-25). */
export interface ComparisonPane {
  readonly networkId: Id;
  /** The network's identity, or null until the project's list has answered. */
  readonly network: Network | null;
  /** The list answered and this id was not in it - deleted, or never this project's. */
  readonly missing: boolean;
  readonly nodes: readonly NetworkNode[];
  readonly links: readonly NetworkLink[];
  readonly structureLoading: boolean;
  readonly structureError: string | null;
  /** The network-scoped topological suite, in registry order. */
  readonly suite: readonly MetricResult[];
  /** The three most critical nodes of this network, worst first. */
  readonly criticality: readonly PaneCriticality[];
  readonly suiteLoading: boolean;
  readonly suiteError: string | null;
}

/**
 * The networks of the side-by-side window, and the one selection they share
 * (FR-25).
 *
 * > "the Compare action opens a new window showing one pane per selected network … each drawing that
 * > network with the read-only miniature of FR-22, above its identity (name, version, baseline and
 * > frozen badges) and its topological suite. Panes share a **by-name element selection**."
 *
 * Route-provided rather than `providedIn: 'root'`, unlike `RunResultsStore`: this window is one
 * screen with one job, and nothing else in the application reads a set of networks at once. It dies
 * with the route.
 *
 * ## What it reads, and on which schedule
 *
 * **The project's network list, once** - `GET /projects/{id}/networks`. One request answers every
 * pane's identity (name, version, `baseline`, `editable`) and, at the same time, the question a URL
 * can always ask: whether an id in the link is still a network of this project. A per-id
 * `GET /networks/{id}` would have been twelve requests to learn the same thing and would have answered
 * a deleted network with a 404 the pane would then have to interpret.
 *
 * It is read here rather than through `NetworksStore`, which already holds exactly this list. That
 * store owns the dashboard's **selection** and its delete machinery (FR-23) - state this window must
 * not have, in a view whose whole contract is that "nothing here edits, runs or deletes". Sharing it
 * would put a `deleteMany` one injection away from a read-only screen and would give a second
 * meaning to a selection signal that already means "the rows ticked on the dashboard".
 *
 * **Each pane's structure, in parallel.** Nodes and links are two cheap reads and the pictures are
 * what the reader is waiting for, so every pane asks at once and each fills in as it lands. They fail
 * *together* within a pane, for `RunResultsStore.loadStructure`'s reason: a miniature with nodes and
 * no arcs is a picture of a network that does not exist.
 *
 * **Each pane's topological suite, one at a time.** This is the deliberate one.
 * `GET /networks/{id}/metrics/topological` both computes and persists, and `NODE_CRITICALITY` is one
 * maximum-flow computation **per node** (FR-04) - the most expensive thing this API does
 * synchronously. Twelve panes firing at once would put twelve of those on a research prototype's
 * single JVM simultaneously and make every pane slow rather than the first pane fast. Sequential is
 * the same judgement `NetworksStore.deleteMany` makes with its `concatMap` and `ProvenanceStore`
 * makes with its sweep, and it has the same escape: a project-scoped
 * `GET /projects/{id}/metrics/topological` would replace the loop and nothing on screen would
 * change. The cap rising to twelve is what makes that sequencing matter rather than
 * merely tidy - and it is now the *only* per-pane cost the larger cap adds, since twelve draws the
 * same grid ten did.
 *
 * **And each is fetched once.** Selecting an element re-reads nothing - the selection is a name, the
 * panes already hold every node and link they need to match it against, and the suite is a property
 * of the network rather than of what is selected in it. That is `TopologicalMetricsStore`'s rule
 * ("only while something is reading it", debounced, never per gesture) applied to a surface that has
 * no edits at all: there is nothing here that could invalidate a suite, so nothing here recomputes
 * one.
 *
 * **Collapsing reads nothing and cancels nothing** (FR-27). See {@link collapsedSuites}: a collapsed
 * pane makes exactly the requests an expanded one makes, at exactly the same time. **Nor does
 * hiding a metric** (FR-31): {@link hiddenMetrics} is a filter over rows already held, and the
 * choice is offered from the codes those rows carry.
 *
 * ## The shared selection is a **name**, and that is the feature
 *
 * {@link selection} is one `ElementKey` for the whole window, not one per pane, for the same
 * reason the period cursor is one signal: two would be two answers to "what is selected" on one
 * screen. Each pane resolves it against its own nodes and links (`element-matching.ts`) and says
 * plainly where there is no match - which is the difference the reader opened the view to find.
 *
 * ## Which suites are collapsed is the **window's** state, and it stops when the window does
 *
 * {@link collapsedSuites} lives here - a signal on a route-provided store - and nowhere else. There
 * is deliberately **no `localStorage`** (FR-27): this window is opened per comparison from
 * the dashboard's actions menu, on a set of networks chosen for that reading, so a collapse
 * remembered across openings would be a preference nobody set - the next comparison, of different
 * networks for a different question, would open in a shape its reader never asked for and cannot
 * see the cause of. That is the **opposite** call from the playback speed, which *is*
 * remembered, and for the opposite reason: a speed applies to a network the researcher returns to
 * again and again, where this applies to one ad-hoc set that will not recur. Reload the window and
 * the default of {@link suitesExpandedByDefault} answers again, which is the correct behaviour for a
 * state whose whole justification is the count of panes in front of you.
 *
 * ## And so is which metrics are shown (FR-31)
 *
 * {@link hiddenMetrics} lives here for the same three reasons, one after another. **It is one set
 * for the window, not one per pane**, because a comparison in which pane 3 prints a figure pane 4
 * has hidden is not a comparison - the same argument that gives {@link selection} one key rather
 * than twelve. **It stores what is hidden**, so a suite that lands later - the suites arrive one
 * pane at a time - is shown rather than silently filtered out by a decision nobody made
 * (`metric-visibility.ts` argues that at length). And **it is not remembered on the device**, for
 * the reason the collapse is not: this window is opened per comparison on a set of networks chosen
 * for one reading, and a filter carried into the next one would hide numbers its reader never chose
 * to hide and could not see the cause of.
 *
 * Putting it in the URL beside `?ids=` was considered and not taken. The ids are the window's
 * *subject* - which configurations - and that is what has to survive a bookmark; which metrics are
 * on screen right now is an arrangement of the reading, like the collapse, and every tick of a
 * checkbox would otherwise be a history entry to press Back through. If sharing a filtered reading
 * turns out to be wanted, a `?metrics=` parsed by the same pure module is the shape of it.
 */
@Injectable()
export class SideBySideStore {
  private readonly api = inject(ApiService);
  private readonly projects = inject(ProjectsStore);

  private readonly _projectId = signal<Id | null>(null);
  private readonly _project = signal<Project | null>(null);
  private readonly _projectError = signal<string | null>(null);

  private readonly _panes = signal<readonly ComparisonPane[]>([]);
  private readonly _listLoading = signal(false);
  private readonly _listError = signal<string | null>(null);

  private readonly _selection = signal<ElementKey | null>(null);

  /**
   * The networks whose structural suite is collapsed, by id (FR-27).
   *
   * A set of ids rather than a flag per pane, because the panes are rebuilt from scratch by every
   * load - `open`, `reload` and the network list landing all write `_panes` whole - and a display
   * state carried inside them would be reset by a retry the reader did not think of as a change of
   * subject. Keyed by network id, it survives all three.
   *
   * It may hold an id whose pane turned out to be missing from the project. That is inert rather
   * than wrong: such a pane has no suite to collapse, so it is left out of {@link collapsibleIds}
   * and of the tally the one control reads, and the id costs nothing while it sits there.
   */
  private readonly _collapsedSuites = signal<ReadonlySet<Id>>(new Set<Id>());

  /**
   * The metrics the reader has **hidden**, by code - one set for the whole window (FR-31).
   *
   * Hidden rather than shown, so that a code nobody has decided about is on screen: the suites land
   * one pane at a time, and a build's registry may hold a calculator this one has never heard of.
   * `metric-visibility.ts` states that argument in full and this is the signal it is
   * about.
   *
   * Like {@link _collapsedSuites} it is keyed by something the panes are rebuilt around rather than
   * held inside them, so a retry does not undo it - and like it, it may hold a code no pane returns.
   * That is inert: {@link metricChoices} is built from the responses, so such a code is offered no
   * checkbox and counted in no total.
   */
  private readonly _hiddenMetrics = signal<ReadonlySet<MetricCode>>(new Set<MetricCode>());

  /**
   * Which load is current, so a window reopened on other ids cannot be filled by the first one.
   *
   * A plain field and not a signal - a guard, not a reading, and nothing renders it. Same shape as
   * `RunResultsStore.elementRequest` and `ProvenanceStore.generation`.
   */
  private generation = 0;

  /** The ids this window was opened on, so re-entering the same URL does not re-read everything. */
  private openedFor = '';

  readonly projectId = this._projectId.asReadonly();
  readonly project = this._project.asReadonly();
  readonly projectError = this._projectError.asReadonly();
  readonly panes = this._panes.asReadonly();
  readonly listLoading = this._listLoading.asReadonly();
  readonly listError = this._listError.asReadonly();

  /** The one element every pane is asked about, or null. */
  readonly selection = this._selection.asReadonly();

  /** Which panes are collapsed to a title and a miniature (FR-27). See {@link _collapsedSuites}. */
  readonly collapsedSuites = this._collapsedSuites.asReadonly();

  /** The metric codes no pane prints (FR-31). Passed to every pane, which filters its own rows. */
  readonly hiddenMetrics = this._hiddenMetrics.asReadonly();

  /** `columns = ⌈√n⌉`, `rows = ⌈n / columns⌉` - the window's shape. */
  readonly grid = computed<PaneGrid>(() => paneGrid(this._panes().length));

  /**
   * The panes that have a suite to collapse at all - every pane whose network is in this project.
   *
   * A missing pane draws one sentence and no metrics, so it is neither collapsed nor expanded; it is
   * excluded here so that "every pane is collapsed" means what it says on screen. Without this, a
   * window holding one deleted network could never reach *Expand all* however many times the reader
   * pressed the control.
   */
  private readonly collapsibleIds = computed<readonly Id[]>(() =>
    this._panes()
      .filter((pane) => !pane.missing)
      .map((pane) => pane.networkId),
  );

  /** How many panes could be collapsed - what the one control's label is counted against. */
  readonly collapsibleCount = computed(() => this.collapsibleIds().length);

  /** How many of those are collapsed right now. */
  readonly collapsedCount = computed(() => {
    const collapsed = this._collapsedSuites();
    return this.collapsibleIds().filter((id) => collapsed.has(id)).length;
  });

  /**
   * The one control above the grid: what it says and what pressing it does (FR-27).
   *
   * The rule is `pane-grid.collapseAllControl` - pure and specced - rather than a ternary here,
   * because "a mixed window reads *Collapse all*" is a decision with a reason, and a reason kept
   * beside the rule is one nobody has to re-derive from the code that happens to implement it.
   */
  readonly collapseAll = computed<CollapseAllControl>(() =>
    collapseAllControl(this.collapsibleCount(), this.collapsedCount()),
  );

  // ------------------------------------------------------------------ which metrics (FR-31)

  /**
   * One checkbox per metric the panes actually returned, in the order the panes print them.
   *
   * Two sources, because a pane prints its numbers in two blocks and the reader chooses over both.
   * The network-scoped rows are {@link ComparisonPane.suite}; `NODE_CRITICALITY` never appears there
   * - it is a per-node metric, so `networkScoped` filters it out and `topCriticality` picks it up
   * instead - and it is what the *Most critical nodes* table is. Offering a box for the suite alone
   * would have left the largest block of figures in every pane outside the control that claims to
   * choose which metrics are shown.
   *
   * Derived from the responses rather than from `TOPOLOGICAL_METRIC_CODES`, so the list grows as the
   * suites land and holds exactly what is on screen. That is also why the state is the *hidden* set:
   * a code that arrives after the reader has ticked something arrives shown.
   */
  readonly metricChoices = computed<readonly MetricOption[]>(() =>
    metricOptions(
      this._panes().flatMap((pane) => {
        const codes: MetricCode[] = pane.suite.map((metric) => metric.metricCode);
        return pane.criticality.length > 0 ? [...codes, MetricCode.NODE_CRITICALITY] : codes;
      }),
    ),
  );

  /** How many of the offered metrics are ticked - what the summary and the two controls read. */
  readonly shownMetricCount = computed(() => {
    const hidden = this._hiddenMetrics();
    return this.metricChoices().filter((option) => !hidden.has(option.code)).length;
  });

  /** What the checkbox row says, and what *Select all* / *Select none* do (FR-31). */
  readonly metricControls = computed<MetricVisibilityControls>(() =>
    metricVisibilityControls(this.metricChoices().length, this.shownMetricCount()),
  );

  /** True while any pane still has a request in flight - the header's one busy indicator. */
  readonly busy = computed(
    () =>
      this._listLoading() ||
      this._panes().some((pane) => pane.structureLoading || pane.suiteLoading),
  );

  /**
   * Open the window on a set of network ids (FR-25).
   *
   * Idempotent on the same ask, because the route's effect re-runs for reasons that are not a change
   * of subject - and re-reading twelve networks and twelve maximum-flow suites because a query parameter
   * was re-emitted would be a page that reloads itself while the reader is using it.
   */
  open(projectId: Id, selection: PaneSelection): void {
    const key = `${projectId}:${selection.ids.join(',')}`;
    if (this.openedFor === key) {
      return;
    }
    this.openedFor = key;
    // A new ask supersedes whatever the last one was still doing, and its failure with it.
    this.generation += 1;
    this._listLoading.set(false);
    this._listError.set(null);
    this._projectId.set(projectId);
    // A selection is a name in *these* networks; carried across a change of subject it would be a
    // question about elements that may not exist in any pane on screen.
    this._selection.set(null);
    this._panes.set(selection.ids.map((id) => emptyPane(id)));
    // The default follows the count, and it is decided here - the one moment the window learns how
    // many panes it has (FR-27). Up to six panes the grid stays within two rows and every suite is
    // open, which is what this window has always done; above that the third row would push the last
    // miniatures off screen, so the panes open as shapes and the numbers are a click away. The hinge
    // is six whatever the cap is: the cap has moved twice and this has not, because it was never a
    // count of networks (`suitesExpandedByDefault`).
    this._collapsedSuites.set(
      suitesExpandedByDefault(selection.ids.length) ? new Set<Id>() : new Set(selection.ids),
    );
    // A new subject starts on every metric (FR-31), like the cleared selection above it: a filter
    // is a question about the numbers *these* configurations report, and carrying one across a
    // change of networks would hide figures of a comparison its reader never filtered.
    this._hiddenMetrics.set(new Set<MetricCode>());
    void this.loadProject(projectId);
    if (selection.ids.length > 0) {
      void this.load(projectId, selection.ids);
    }
  }

  /**
   * Read everything again - the header's retry, after a failure the reader has fixed.
   *
   * The collapse state is deliberately **not** reset with the data, and neither is the metric
   * filter (FR-31). A retry is the same ask on the same networks, and a reader who collapsed eight
   * panes to look at two - or unticked seven metrics to compare the other two - would find their
   * arrangement undone by a button that promised only to read again. The defaults of {@link open}
   * are for a new subject; this is not one.
   */
  reload(): void {
    const projectId = this._projectId();
    if (projectId === null) {
      return;
    }
    const ids = this._panes().map((pane) => pane.networkId);
    this._panes.set(ids.map((id) => emptyPane(id)));
    void this.loadProject(projectId);
    void this.load(projectId, ids);
  }

  // ------------------------------------------------------------------ collapse (FR-27)

  /** Whether this pane is showing a title and a miniature rather than its numbers. */
  isSuiteCollapsed(networkId: Id): boolean {
    return this._collapsedSuites().has(networkId);
  }

  /**
   * Collapse or expand one pane's structural suite, from that pane's own header (FR-27).
   *
   * **Nothing is fetched and nothing is cancelled.** This is a display state over data the window
   * already holds: the suite was requested when the window opened, whatever the pane looked like at
   * the time, and it stays requested. A collapsed pane that skipped its request would be a pane with
   * nothing to show the moment it was expanded - a spinner as the reward for a click that was
   * supposed to reveal an answer already computed. See the class note's schedule.
   */
  toggleSuite(networkId: Id): void {
    this._collapsedSuites.update((collapsed) => {
      const next = new Set(collapsed);
      if (!next.delete(networkId)) {
        next.add(networkId);
      }
      return next;
    });
  }

  /**
   * Take **every** pane to one state - what the control above the grid does (FR-27).
   *
   * The label rule guarantees the argument: `collapseAll().collapse` is what the button on screen
   * says it will do, so a mixed window collapses the rest rather than flipping each pane and leaving
   * the reader to work out which half moved. Nothing is fetched here either.
   */
  setAllSuites(collapse: boolean): void {
    this._collapsedSuites.set(collapse ? new Set(this.collapsibleIds()) : new Set<Id>());
  }

  /** Press the one control: whatever its label promises, applied to every pane. */
  applyCollapseAll(): void {
    this.setAllSuites(this.collapseAll().collapse);
  }

  // ------------------------------------------------------------------ which metrics (FR-31)

  /** Whether every pane prints this metric. A code nobody has hidden is shown. */
  isMetricShown(code: MetricCode): boolean {
    return !this._hiddenMetrics().has(code);
  }

  /**
   * Tick or untick one metric, for every pane at once (FR-31).
   *
   * **Nothing is fetched and nothing is cancelled**, exactly as with the collapse: the suite was
   * read when the window opened and the whole of it is still held, so this is a filter over rows in
   * hand. Unticking `DENSITY` does not make the next pane's suite cheaper, and ticking it back does
   * not cost a request.
   */
  toggleMetric(code: MetricCode): void {
    this._hiddenMetrics.update((hidden) => {
      const next = new Set(hidden);
      if (!next.delete(code)) {
        next.add(code);
      }
      return next;
    });
  }

  /**
   * *Select all* / *Select none* - both destinations, one press each (FR-31).
   *
   * Only the **offered** codes are written, so hiding everything hides what is on screen rather than
   * every code the registry has ever emitted. That keeps {@link _hiddenMetrics} a statement about
   * this window's panes and lets a suite that lands afterwards be shown, which is the default the
   * whole hidden-not-shown choice exists to preserve.
   */
  setAllMetrics(shown: boolean): void {
    this._hiddenMetrics.set(
      shown
        ? new Set<MetricCode>()
        : new Set<MetricCode>(this.metricChoices().map((option) => option.code)),
    );
  }

  // ------------------------------------------------------------------ the shared selection

  /**
   * Select an element by name across every pane - or drop the selection by picking it again.
   *
   * The second click is what makes the gesture reversible without a second control: the
   * miniature's empty space means "the whole network", and in this window that is *no element
   * selected*, so both roads lead to the same place.
   */
  select(key: ElementKey): void {
    this._selection.update((current) => (sameKey(current, key) ? null : key));
  }

  clearSelection(): void {
    this._selection.set(null);
  }

  // ------------------------------------------------------------------------- loading

  private async loadProject(projectId: Id): Promise<void> {
    this._projectError.set(null);
    try {
      const project = await firstValueFrom(this.projects.get(projectId));
      if (this._projectId() === projectId) {
        this._project.set(project);
      }
    } catch (failure: unknown) {
      if (this._projectId() === projectId) {
        this._project.set(null);
        // A failure here costs the window its title and nothing else, so it is held apart from the
        // list's error the way the dashboard holds its criticality suite apart from its run.
        this._projectError.set(problemMessage(failure, 'Could not read this project’s name.'));
      }
    }
  }

  private async load(projectId: Id, ids: readonly Id[]): Promise<void> {
    const generation = ++this.generation;
    this._listLoading.set(true);
    this._listError.set(null);
    let networks: readonly Network[];
    try {
      networks = await firstValueFrom(
        this.api.get<Network[]>(`/projects/${projectId}/networks`),
      );
    } catch (failure: unknown) {
      if (generation === this.generation) {
        this._listLoading.set(false);
        this._listError.set(
          problemMessage(failure, 'Could not read this project’s networks, so no pane can be drawn.'),
        );
        // The panes are settled rather than left spinning: no request will be made for them, and a
        // spinner that never resolves is the one thing worse than the banner above it.
        this._panes.update((panes) =>
          panes.map((pane) => ({
            ...pane,
            structureLoading: false,
            suiteLoading: false,
            structureError: 'This project’s network list could not be read.',
            suiteError: 'Not attempted - the network list could not be read.',
          })),
        );
      }
      return;
    }
    if (generation !== this.generation) {
      return;
    }

    const byId = new Map(networks.map((network) => [network.id, network]));
    this._panes.set(
      ids.map((id) => {
        const network = byId.get(id) ?? null;
        return {
          ...emptyPane(id),
          network,
          missing: network === null,
          // A pane with no network has nothing to read, so it never enters either loading state -
          // otherwise it would spin for a request that is never going to be made.
          structureLoading: network !== null,
          suiteLoading: network !== null,
        };
      }),
    );
    this._listLoading.set(false);

    const present = ids.filter((id) => byId.has(id));
    // Structures in parallel: the pictures are what the reader is waiting for, and these are two
    // ordinary reads apiece.
    await Promise.all(present.map((id) => this.loadStructure(id, generation)));
    // Suites one at a time: `NODE_CRITICALITY` is a maximum-flow per node, and twelve at once would
    // make every pane slow rather than the first pane fast (see the class note). Every pane asks,
    // including the ones that opened collapsed - a collapse is a display state, not a reason to
    // arrive at an expanded pane with nothing in it (FR-27).
    for (const id of present) {
      if (generation !== this.generation) {
        return;
      }
      await this.loadSuite(id, generation);
    }
  }

  /**
   * One pane's nodes and links - the miniature's whole input (FR-22's drawing).
   *
   * Through `core/api-nulls.ts`, because both carry omitted fields the drawing branches on:
   * `posX`/`posY` decide whether the miniature uses the researcher's layout or falls back to
   * echelons, and an omitted field passes every `!== null` test, silently and inverted.
   * In this window the layout question is sharper than on the dashboard - two variants drawn by two
   * different arrangements cannot be compared by eye, and the pane says which one it drew.
   */
  private async loadStructure(networkId: Id, generation: number): Promise<void> {
    try {
      const [nodes, links] = await Promise.all([
        firstValueFrom(this.api.get<WireNode[]>(`/networks/${networkId}/nodes`)),
        firstValueFrom(this.api.get<WireLink[]>(`/networks/${networkId}/links`)),
      ]);
      if (generation !== this.generation) {
        return;
      }
      this.patch(networkId, {
        nodes: normaliseNodes(nodes),
        links: normaliseLinks(links),
        structureLoading: false,
        structureError: null,
      });
    } catch (failure: unknown) {
      if (generation !== this.generation) {
        return;
      }
      this.patch(networkId, {
        nodes: [],
        links: [],
        structureLoading: false,
        structureError: problemMessage(
          failure,
          'Could not read this network, so it is not drawn.',
        ),
      });
    }
  }

  /**
   * One pane's topological suite - `GET /networks/{id}/metrics/topological`.
   *
   * A failure costs this pane its numbers and leaves its picture, exactly as the results dashboard's
   * criticality read costs it the table and not the run: a suite that would not compute is no reason
   * to withhold a network's shape, and in a window of twelve the reader can still compare the other
   * eleven against each other.
   */
  private async loadSuite(networkId: Id, generation: number): Promise<void> {
    try {
      const response = await firstValueFrom(
        this.api.get<WireTopologicalResponse>(`/networks/${networkId}/metrics/topological`),
      );
      if (generation !== this.generation) {
        return;
      }
      // Structural metrics are exact and carry no interval, so every row arrives with `ciLow`/
      // `ciHigh` absent - and `ci-value` decides it has an interval with `!== null`.
      const metrics = normaliseMetrics(response.metrics);
      this.patch(networkId, {
        suite: networkScoped(metrics),
        criticality: topCriticality(metrics),
        suiteLoading: false,
        suiteError: null,
      });
    } catch (failure: unknown) {
      if (generation !== this.generation) {
        return;
      }
      this.patch(networkId, {
        suite: [],
        criticality: [],
        suiteLoading: false,
        suiteError: problemMessage(
          failure,
          'Could not compute this network’s topological metrics.',
        ),
      });
    }
  }

  /** Rewrite one pane. A new array each time, because a signal holding a mutated one is silent. */
  private patch(networkId: Id, change: Partial<ComparisonPane>): void {
    this._panes.update((panes) =>
      panes.map((pane) => (pane.networkId === networkId ? { ...pane, ...change } : pane)),
    );
  }
}

/**
 * Whether this pane is in a position to answer the shared selection at all (FR-25).
 *
 * A pane still reading its nodes, one whose read failed, and one whose network is not in the project
 * have **no answer** - which is not the same as answering *no*. Saying "DC-1 is not in Baseline v3"
 * about a network this window has not finished reading would be the one thing worse than saying
 * nothing: a claimed structural difference that is really a request in flight.
 *
 * Exported because two surfaces apply it - the pane, deciding whether to print its match line, and
 * the window, counting *in 3 of 4* - and the two must not disagree about which panes are being
 * counted.
 */
export function paneHasAnswered(pane: ComparisonPane): boolean {
  return !pane.missing && !pane.structureLoading && pane.structureError === null;
}

/** How many criticality rows a pane shows - the network dashboard's own three (FR-19). */
const CRITICALITY_ROWS = 3;

function emptyPane(networkId: Id): ComparisonPane {
  return {
    networkId,
    network: null,
    missing: false,
    nodes: [],
    links: [],
    structureLoading: true,
    structureError: null,
    suite: [],
    criticality: [],
    suiteLoading: true,
    suiteError: null,
  };
}

/**
 * The network-scoped rows in the registry's own order.
 *
 * `TOPOLOGICAL_METRIC_CODES` rather than the order the response arrived in, and the same rank rule
 * `TopologicalMetricsStore.networkMetrics` uses: twelve panes stacked in a grid have to list their
 * metrics in the same order or the reader is comparing rows that are not beside each other. A code
 * this build has never heard of sorts last rather than being dropped - calculators are discovered at
 * runtime.
 *
 * The comparator itself is `metric-visibility.compareMetricCodes` rather than one written here,
 * because FR-31's checkbox row is ordered by it too: the boxes and the rows they govern are two
 * renderings of one order, and the third box down has to be the third row down. That is also where
 * the tie-break between two codes this build cannot rank lives - a detail invisible until it is the
 * one thing making the filter aim wrong.
 */
function networkScoped(metrics: readonly MetricResult[]): readonly MetricResult[] {
  return metrics
    .filter((metric) => metric.scope === MetricScope.NETWORK)
    .sort((a, b) => compareMetricCodes(a.metricCode, b.metricCode));
}

/**
 * The most critical nodes of one network, worst first, capped at three.
 *
 * The bar is scaled to the largest value in **this** network rather than across the window, and that
 * is deliberate: `NODE_CRITICALITY` is already a share of that network's own serviceable demand, so
 * a bar normalised across panes would be comparing two shares of two different denominators and
 * drawing the answer as though it were one. The percentages beside the bars are the comparable
 * figures; the bars say which node dominates *its own* configuration.
 */
function topCriticality(metrics: readonly MetricResult[]): readonly PaneCriticality[] {
  const rows: { nodeId: Id; name: string; value: number }[] = [];
  for (const metric of metrics) {
    if (
      metric.metricCode !== MetricCode.NODE_CRITICALITY ||
      metric.scope !== MetricScope.NODE ||
      metric.scopeId === null
    ) {
      continue;
    }
    rows.push({
      nodeId: metric.scopeId,
      name: metric.scopeName ?? `#${metric.scopeId}`,
      value: metric.value,
    });
  }
  rows.sort((a, b) => b.value - a.value || a.name.localeCompare(b.name));
  const highest = rows.length ? rows[0].value : 0;
  return rows
    .slice(0, CRITICALITY_ROWS)
    .map((row) => ({ ...row, share: highest > 0 ? row.value / highest : 0 }));
}
