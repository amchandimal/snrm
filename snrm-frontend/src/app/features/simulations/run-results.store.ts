import { Injectable, OnDestroy, computed, inject, signal, untracked } from '@angular/core';
import { Subscription, firstValueFrom } from 'rxjs';

import {
  WireLink,
  WireNode,
  WireResults,
  WireTopologicalResponse,
  normaliseLinks,
  normaliseMetrics,
  normaliseNodes,
  normaliseResults,
} from '../../core/api-nulls';
import { ApiService } from '../../core/api.service';
import {
  ELEMENT_SERIES_UNAVAILABLE,
  ELEMENT_SERIES_UNREADABLE,
  ElementSeriesIndex,
  elementSeriesPath,
  indexElementSeries,
} from '../../core/element-series';
import { JobPollingService } from '../../core/job-polling.service';
import { periodReadout } from '../../core/metric-display';
import {
  ElementTimeseries,
  Id,
  Job,
  LinkTimeseries,
  MetricCode,
  MetricResult,
  MetricScope,
  NetworkLink,
  NetworkNode,
  NodeTimeseries,
  SimulationResults,
  SimulationRun,
  isTerminalJobStatus,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { chartId } from './element-charts';
import { MiniMapSelection, WHOLE_NETWORK } from './mini-map-layout';
import { FILL_RATE_CHART } from './network-charts';
import { clampPeriod, defaultCursorPeriod, steppedPeriod } from './period-cursor';
// Aliased because two of them share a name with the computed signals below. The signal is what the
// dashboard reads; these are the arithmetic behind it, and leaving both called `lossRegions` in one
// file would make every reader check which is which.
import {
  CurvePoint,
  LossRegion,
  lossRegions as computeLossRegions,
  shadedArea as computeShadedArea,
  toCurve,
} from './curve-geometry';

export type ResultsState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * What the page is describing (FR-22).
 *
 * > "Clicking a node, a link, or empty space selects the scope the page describes: the whole network
 * > (the existing suite and curves), or one element."
 *
 * `network` is the dashboard exactly as it was before FR-22 - nothing is gated on the scope being an
 * element except the element charts themselves, so a run with no per-element series loses nothing it
 * ever had.
 *
 * It **is** `MiniMapSelection` rather than a type of the same shape: the page's scope and the
 * miniature's selection are one signal here, so declaring them separately would be two names for one
 * value, free to drift the day either gains a case. The declaration lives beside the drawing because
 * the side-by-side window of FR-25 selects the same three things in a picture that has no page scope
 * at all.
 */
export type DashboardScope = MiniMapSelection;

/** The scope a run opens on, and the one an empty-space click returns to. */
export const NETWORK_SCOPE: DashboardScope = WHOLE_NETWORK;

/** The empty answer to "this run's link series", shared so its identity is stable. */
const NO_LINK_SERIES: ReadonlyMap<Id, LinkTimeseries> = new Map<Id, LinkTimeseries>();

/** One node's criticality, ready to render. */
export interface CriticalityRow {
  readonly nodeId: Id;
  readonly name: string;
  readonly value: number;
  /** `value` as a fraction of the largest in this network - the bar width, not the metric. */
  readonly share: number;
}

/**
 * One completed run, with everything the dashboard shows.
 *
 * > "Results dashboard - fill-rate-vs-period curve with baseline overlay and shaded loss area (the
 * > resilience triangle rendered literally); metric cards with CIs; per-node criticality table."
 *
 * ## Two fetches, and why the second one exists
 *
 * `GET /simulations/{runId}` carries the run, its simulated metrics and its curves in one response.
 * The criticality table is not in it, and could not be: `NODE_CRITICALITY` is a
 * *topological* metric, a property of the network rather than of any run, so it comes
 * from `GET /networks/{id}/metrics/topological`.
 *
 * That endpoint recomputes as it reads, which is one maximum-flow per node - not free. It is called
 * once, on open, and never on a refresh of the run: the network is frozen for the whole life of a
 * locking run, so its structural metrics cannot have changed since. A failure there leaves
 * the rest of the dashboard intact, because a criticality table that would not compute is no reason
 * to withhold the run's results.
 *
 * ## A run that is still executing is a legitimate thing to open
 *
 * The API answers a `QUEUED` or `RUNNING` run with its record and two empty lists rather than a 404,
 * precisely so the dashboard can open on a run the moment it is submitted. Pass the `jobId` to
 * {@link follow} and the page gets live progress and a cancel button through `JobPollingService`;
 * without one - a bookmarked URL, a reload - it falls back to a refresh button, since the job id is
 * only ever handed out at submission.
 *
 * ## Two more reads since FR-22, and they are fetched on opposite schedules
 *
 * **The network's structure - nodes and links - is read on open**, because the network inspector is
 * on the page from the moment it renders (it sits in the top-left corner) and a miniature
 * with nothing in it is not a degraded miniature, it is a hole. It is read **once per network id**
 * and not again: the network is frozen for the whole life of a locking run, so its nodes
 * and links cannot change underneath this page, and the poll settling would otherwise spend two
 * requests re-reading a structure that is immutable by construction. A failure leaves the rest of the
 * dashboard intact, exactly as the criticality suite's does - it costs the miniature, not the run.
 *
 * **The per-element series is read on the first element scope**, and never for a reader who does not
 * take one. It is `horizon × (nodes + links)` numbers - which is why it is kept out of
 * `GET /simulations/{runId}` at all - and the dashboard's default scope is the network, whose curve,
 * cards and criticality table need none of it. Spending it on page open would make every visit to
 * every run pay for a chart most visits never draw. So {@link selectNode} and {@link selectLink} are
 * where it is asked for, once per run, and the whole horizon then sits in memory for as long as the
 * run does: switching between elements, and the period cursor to come, cost no request at all. That
 * is the same trade `PlaybackStore` makes one feature across, taken at a different moment because the
 * editor's canvas needs the series the instant a run completes and this page does not.
 *
 * A read that **failed** is retried by the next element click, while `available: false` is not: the
 * first might succeed, and the second is a durable fact about the run.
 *
 * ## And one cursor, which is what makes the page one page (FR-22)
 *
 * {@link cursorPeriod} is a single integer in `[0, horizon − 1]`, and *every* per-period surface on
 * the dashboard reads it: the performance curve's line, each of an element scope's charts, the
 * network figures beneath the curve, and the miniature's tints. One period cursor is shared by
 * every chart on the page, and the reason it is a signal on this store rather than an input
 * threaded down through four components is the failure that would otherwise follow:
 * two cursors that could drift would put the same period in two places on one screen.
 *
 * It is **not** a clock. There is no loop here, no timer and no `requestAnimationFrame` anywhere in
 * this feature: the dashboard navigates a run, and animating one is the editor's playback.
 * `period-cursor.ts` holds the arithmetic and has no `advance` in it for that reason.
 *
 * Three rules travel with it. The horizon is the **run's own record** (`horizonPeriods`), like every
 * other reading on this page. The cursor **opens on the last period** - the end state, which is what
 * a reader means by "the result" until they scrub - and is re-defaulted only when the run *changes*,
 * so a Refresh does not rewind a reader's position. And **scoping does not move it**: switching
 * between the network and an element is a change of subject, not of period, so
 * {@link selectNode}, {@link selectLink} and {@link selectNetwork} leave it exactly where it is.
 *
 * The transport is also the third gesture that asks for the element series, beside the two scope
 * gestures - the tints are the one thing on the page that needs it, and the *default* position asks
 * for nothing, so a reader who opens a run and reads its curve still spends no request on
 * `horizon × (nodes + links)` numbers.
 */
@Injectable({ providedIn: 'root' })
export class RunResultsStore implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly jobs = inject(JobPollingService);

  private readonly _runId = signal<Id | null>(null);
  private readonly _results = signal<SimulationResults | null>(null);
  private readonly _state = signal<ResultsState>('idle');
  private readonly _error = signal<string | null>(null);

  private readonly _topological = signal<readonly MetricResult[]>([]);
  private readonly _topologicalError = signal<string | null>(null);

  // ------------------------------------------------------- the network inspector (FR-22)

  private readonly _scope = signal<DashboardScope>(NETWORK_SCOPE);
  private readonly _nodes = signal<readonly NetworkNode[]>([]);
  private readonly _links = signal<readonly NetworkLink[]>([]);
  private readonly _structureError = signal<string | null>(null);

  /** The network id whose structure is held, so a poll settling does not re-read a frozen network. */
  private structureNetworkId: Id | null = null;

  // ------------------------------------------------------------ the element series (FR-22)

  private readonly _elements = signal<ElementSeriesIndex | null>(null);
  private readonly _elementsAvailable = signal(false);
  private readonly _elementsLoading = signal(false);
  private readonly _elementNote = signal<string | null>(null);

  /** The run whose element series is held, or null when the next element scope must fetch it. */
  private elementsRunId: Id | null = null;

  /** Which element read is current, so a slow answer for a run the reader has left cannot land. */
  private elementRequest = 0;

  // ------------------------------------------------------------- the period cursor (FR-22)

  private readonly _cursor = signal(0);

  /**
   * The run the cursor has been positioned for, so re-reading a run does not rewind it.
   *
   * A plain field rather than a signal, and the same shape as {@link structureNetworkId} beside it:
   * it is a *guard*, not a reading, and nothing renders it. Refresh calls {@link load} on the run
   * already open (and so does the poll settling, every few seconds while a run finishes), and a
   * reader parked on period 11 of a disruption window must not be thrown back to the end of the
   * horizon by a request they did not make.
   */
  private cursorRunId: Id | null = null;

  // -------------------------------------------------------------------- enlarged charts

  /**
   * Which charts are drawn enlarged rather than as one of the grid.
   *
   * The fill-rate curve starts enlarged, and nothing else does: it is the chart this page has always
   * been built around - the resilience triangle rendered literally - and the other six are the
   * per-period series a reader scans and then asks a question of. Held here rather than in a
   * component because two panels draw charts (the network scope and an element scope) and the
   * gesture must mean the same thing in both.
   */
  private readonly _expandedCharts = signal<ReadonlySet<string>>(
    new Set([chartId('network', FILL_RATE_CHART)]),
  );

  readonly expandedCharts = this._expandedCharts.asReadonly();

  private readonly _job = signal<Job | null>(null);
  private readonly _cancelling = signal(false);
  private readonly _deleting = signal(false);

  readonly runId = this._runId.asReadonly();
  readonly results = this._results.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  readonly topologicalError = this._topologicalError.asReadonly();
  readonly job = this._job.asReadonly();
  readonly cancelling = this._cancelling.asReadonly();
  /** True while `DELETE /simulations/{runId}` is in flight (FR-20). */
  readonly deleting = this._deleting.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');
  readonly run = computed(() => this._results()?.run ?? null);

  // ---------------------------------------------------------------- the scope (FR-22)

  /** What the page is describing: the whole network, or one element of it. */
  readonly scope = this._scope.asReadonly();

  readonly scopeKind = computed(() => this._scope().kind);

  /** True for the dashboard exactly as it was before FR-22. */
  readonly isNetworkScope = computed(() => this._scope().kind === 'network');

  /** The run's network as the miniature draws it - read once, never written back. */
  readonly nodes = this._nodes.asReadonly();
  readonly links = this._links.asReadonly();

  /** Why the miniature is empty, or null. Held apart from {@link error}, like the suite's. */
  readonly structureError = this._structureError.asReadonly();

  /** The scoped node, or null - including when the scope names an id the structure has no row for. */
  readonly scopedNode = computed<NetworkNode | null>(() => {
    const scope = this._scope();
    return scope.kind === 'node'
      ? (this._nodes().find((node) => node.id === scope.id) ?? null)
      : null;
  });

  readonly scopedLink = computed<NetworkLink | null>(() => {
    const scope = this._scope();
    return scope.kind === 'link'
      ? (this._links().find((link) => link.id === scope.id) ?? null)
      : null;
  });

  // ------------------------------------------------------- the element series (FR-22)

  /** Whether this run recorded a per-element series at all (`available`). */
  readonly elementsAvailable = this._elementsAvailable.asReadonly();

  /** True while the element read is in flight - the panel waits rather than flashing a sentence. */
  readonly elementsLoading = this._elementsLoading.asReadonly();

  /**
   * The one line shown in place of the element charts, or null when there are charts to draw.
   *
   * Verbatim from `core/element-series.ts`, which is where the editor's transport bar and element
   * inspector take the same two sentences: two surfaces phrasing one situation differently read as
   * two problems (FR-18).
   */
  readonly elementNote = computed(() => {
    if (this._elementsLoading() || this._elementsAvailable()) {
      return null;
    }
    return this._elementNote();
  });

  /** The scoped node's whole horizon, or null where there is none to draw. */
  readonly scopedNodeSeries = computed<NodeTimeseries | null>(() => {
    const node = this.scopedNode();
    return node ? (this._elements()?.nodes.get(node.id) ?? null) : null;
  });

  readonly scopedLinkSeries = computed<LinkTimeseries | null>(() => {
    const link = this.scopedLink();
    return link ? (this._elements()?.links.get(link.id) ?? null) : null;
  });

  /**
   * The whole element index, or null - what the miniature tints itself from (FR-22).
   *
   * Exposed as the index rather than as tints because the tints depend on the cursor and the index
   * does not: `period-cursor.fillScales` walks it once per run and `cursorTints` once per step, and
   * that split is what keeps a step from re-deriving a normalising maximum. Null covers all of "not
   * read yet", "the run recorded none" and "the read failed" - the miniature draws untinted in every
   * one of them, exactly as the canvas leaves its elements in their ordinary styling
   * (`applyPlayback`), and {@link elementNote} is what distinguishes them in words.
   */
  readonly elements = this._elements.asReadonly();

  /**
   * What the miniature's tint is, or why there is none - one line under its legend (FR-22).
   *
   * Here rather than in `network-inspector` since FR-25 generalised that component to draw *a*
   * network: every one of these four sentences is a statement about a **run**, and the store is what
   * has one. A miniature with no run behind it - a side-by-side pane - passes no note and the line
   * is not drawn, which is the honest rendering of "there is nothing about a run to say".
   *
   * Four states, and each is a different fact rather than a degree of the same one. A run that
   * recorded no element detail shows `core/element-series.ts`'s sentence **verbatim**, the same
   * string the editor's transport bar and this page's element charts show (FR-18): three
   * surfaces, one situation, one phrasing.
   */
  readonly tintNote = computed<string>(() => {
    if (!this.hasCursor()) {
      // No periods to stand in - a run still executing, or one whose series is not written yet. The
      // map is a picture of the network either way, which is why it is drawn rather than withheld.
      return 'This run has no periods to move through yet, so the map is drawn untinted.';
    }
    if (this._elementsLoading()) {
      return 'Reading this run’s element detail, which is what the map is tinted from…';
    }
    const stated = this.elementNote();
    if (stated !== null) {
      return `${stated} The map is drawn untinted; every chart and figure on the page still moves `
        + 'with the cursor.';
    }
    if (this._elements() === null) {
      return 'Move the period cursor to tint this map with each period’s availability and fill.';
    }
    return 'Tinted at the cursor: a node is filled to its own on-hand against its own horizon '
      + 'maximum, and a red halo is availability lost to a disruption.';
  });

  /** Every link's series, for the inbound-flow weights of a node's average lead. */
  readonly linkSeries = computed<ReadonlyMap<Id, LinkTimeseries>>(
    // One shared empty map rather than a fresh one, so a run with no series does not hand every
    // downstream computed a new identity on each read.
    () => this._elements()?.links ?? NO_LINK_SERIES,
  );

  /** The evaluated network's clock - the x-axis unit and TTR's meaning both come from it. */
  readonly periodLength = computed(() => this.run()?.periodLength ?? null);

  /** True while the run has not reached a terminal state; the dashboard then shows progress. */
  readonly stillRunning = computed(() => {
    const run = this.run();
    return run !== null && !isTerminalJobStatus(run.status);
  });

  readonly progressPercent = computed(() => {
    const job = this._job();
    return job ? Math.max(0, Math.min(100, Math.round(job.progress * 100))) : 0;
  });

  // ------------------------------------------------------------------------- the curve

  /** Both series as fill rates, ready to plot. */
  readonly curve = computed<readonly CurvePoint[]>(() =>
    toCurve(this._results()?.timeseries ?? []),
  );

  /** The stretches where the disrupted curve runs below its baseline - the triangle. */
  readonly lossRegions = computed<readonly LossRegion[]>(() => computeLossRegions(this.curve()));

  /**
   * The area the shading covers, in fill-rate × periods.
   *
   * Shown beside the `LOSS_AREA` card, not instead of it. The two answer the same question about
   * different objects - this measures the mean curves, the metric averages the per-replication
   * triangles - and they coincide only on a deterministic run. See `curve-geometry.ts`.
   */
  readonly shadedArea = computed(() => computeShadedArea(this.lossRegions()));

  readonly hasCurve = computed(() => this.curve().length > 0);

  // ------------------------------------------------------------- the period cursor (FR-22)

  /** The one period every chart, every figure and every tint on the page is read at. */
  readonly cursorPeriod = this._cursor.asReadonly();

  /**
   * How many periods the cursor may stand in - **the run's own record** (FR-22).
   *
   * `run.horizonPeriods`, not the length of the series it delivered, for the reason every other
   * reading on this page takes the run's record: it is what "of 52" restates, and it is the run's
   * own declaration rather than a count this client took of a response. The two cannot disagree
   * where it matters - `RUN_TIMESERIES` is written whole when a run completes, so a run that has a
   * curve at all has every period of it - and where there is no curve there is no transport
   * ({@link hasCursor}), which is the honest answer for a `QUEUED` run rather than a scrub bar over
   * periods that do not exist yet.
   */
  readonly cursorHorizon = computed(() => Math.max(0, this.run()?.horizonPeriods ?? 0));

  /** Highest period the cursor may stand on - the scrub slider's `max`. */
  readonly lastCursorPeriod = computed(() => Math.max(0, this.cursorHorizon() - 1));

  /** True when there is a run with periods to move through, which is what renders the transport. */
  readonly hasCursor = computed(() => this.cursorHorizon() > 0 && this.hasCurve());

  /**
   * `Period 14 of 52 - 14 days` - the cursor, restated through the **run's** clock.
   *
   * Never a bare index: the same 52-period horizon is a year on one network and two days on
   * another. The string is `core/metric-display.periodReadout`, which the editor's playback
   * transport also prints, so one researcher reading period 14 of one run on two screens is shown
   * one sentence.
   */
  readonly cursorLabel = computed(() =>
    periodReadout(this._cursor(), this.cursorHorizon(), this.periodLength()),
  );

  // ---------------------------------------------------------------------- metric cards

  /**
   * The simulated suite in the registry's own order.
   *
   * The API returns rows in calculator order already; this preserves it rather than re-sorting,
   * because that order is the suite's presentation order and re-deriving it here would be a second
   * opinion about it.
   */
  readonly metricCards = computed<readonly MetricResult[]>(() =>
    (this._results()?.metrics ?? []).filter((metric) => metric.scope === MetricScope.NETWORK),
  );

  /**
   * The criticality table, worst first.
   *
   * The bar is scaled to the largest value in this network rather than to 1: a network whose worst
   * node scores 0.2 has no dominant weak point, and six near-empty bars would say that far less
   * clearly than six bars whose lengths differ.
   */
  readonly criticalityRows = computed<readonly CriticalityRow[]>(() => {
    const rows: { nodeId: Id; name: string; value: number }[] = [];
    for (const metric of this._topological()) {
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
    return rows.map((row) => ({ ...row, share: highest > 0 ? row.value / highest : 0 }));
  });

  /** The network-scoped structural metrics, for the dashboard's secondary strip. */
  readonly structuralMetrics = computed<readonly MetricResult[]>(() =>
    this._topological().filter((metric) => metric.scope === MetricScope.NETWORK),
  );

  // ------------------------------------------------------------------------- loading

  private poll: Subscription | null = null;

  dismissError(): void {
    this._error.set(null);
    this._topologicalError.set(null);
    this._structureError.set(null);
  }

  // ------------------------------------------------------------------ the scope (FR-22)

  /**
   * Scope the page to one node - the miniature's click, and the criticality table's row.
   *
   * **One selection, two representations.** The table and the miniature write the same signal, so a
   * row click lights the node in the picture and a node click lights the row; two selections that
   * could disagree would be two answers to "which node is this page about" on one screen.
   *
   * This is also where the element series is fetched, once per run - see the class note. Selecting an
   * element is the first gesture that needs it, and selecting a *second* one needs nothing.
   */
  selectNode(nodeId: Id): void {
    this._scope.set({ kind: 'node', id: nodeId });
    this.ensureElementSeries();
  }

  /** Scope the page to one link. See {@link selectNode}. */
  selectLink(linkId: Id): void {
    this._scope.set({ kind: 'link', id: linkId });
    this.ensureElementSeries();
  }

  /**
   * Back to the whole network - a click on empty space in the miniature.
   *
   * Fetches nothing and clears nothing: the series stays in memory for the next element click, and
   * the network scope is the page as it always was. **The cursor does not move either** - see the
   * class note: changing what the page is about is not changing which period it is showing, and a
   * scope change that rewound the cursor would lose the reader's place every time they compared an
   * element against the whole network at one period, which is the comparison the scope exists for.
   */
  selectNetwork(): void {
    this._scope.set(NETWORK_SCOPE);
  }

  // ------------------------------------------------------------- the period cursor (FR-22)

  /**
   * Put the cursor on a period - the scrub slider's write path, clamped into the run.
   *
   * **A transport gesture is also what asks for the element series.** The miniature's tints are the
   * one thing on this page that needs it, and a reader moving the cursor has asked for exactly that;
   * the default position asks for nothing, which is what keeps a visit that only reads the curve
   * free of a `horizon × (nodes + links)` response. {@link ensureElementSeries} is guarded, so
   * dragging the slider across fifty periods issues one request and not fifty.
   */
  setCursor(period: number): void {
    const next = clampPeriod(period, this.cursorHorizon());
    if (next !== this._cursor()) {
      this._cursor.set(next);
    }
    this.ensureElementSeries();
  }

  /** Step the cursor by whole periods - the step buttons and the arrow keys (FR-22). */
  stepCursor(delta: number): void {
    this.setCursor(steppedPeriod(this._cursor(), delta, this.cursorHorizon()));
  }

  // --------------------------------------------------------------------- enlarged charts

  /**
   * Whether a chart is drawn enlarged - what the double-click toggles.
   *
   * The identity is `chartId(scope, key)`, so the network's `onHand` and a node's `onHand` are two
   * charts and enlarging one does not enlarge the other when the reader changes scope.
   */
  isChartExpanded(id: string): boolean {
    return this._expandedCharts().has(id);
  }

  /**
   * Enlarge a chart, or return it to the grid - one double-click, both ways.
   *
   * **A set rather than a single value**, so several charts can be open at once: comparing period
   * cost against cumulative unmet demand is exactly the reading the grid exists to make possible, and
   * a single-expansion model would collapse one to open the other. A new `Set` on every toggle,
   * because a signal holding a mutated collection is a signal that never reports a change.
   *
   * It is **not** reset when the run changes. Which charts a reader wants open is a preference about
   * how they read, not a fact about the run - the same reasoning that keeps the playback speed out of
   * the network row - so opening a second run leaves the page arranged as they left it.
   */
  toggleChartSize(id: string): void {
    const next = new Set(this._expandedCharts());
    if (!next.delete(id)) {
      next.add(id);
    }
    this._expandedCharts.set(next);
  }

  /**
   * Route entry: show this run, and follow its job if the caller was told one.
   *
   * **The one entry point a route may call, and it re-reads every time.** What it replaces is a
   * component-side `runId !== store.runId()` test that looks like a cache check and is not one: this
   * store is `providedIn: 'root'`, so its run id says which run it *last pointed at*, never whether
   * what it holds is worth showing. Three ordinary states failed that test - a run still `RUNNING`
   * when the page was first opened, a load that failed, and a load that failed and had its banner
   * dismissed - and in each of them, coming back to the run showed the stale answer with nothing on
   * the network tab to explain it. A run is a durable row that changes underneath its reader,
   * so arriving at its page is a reason to read it.
   *
   * Switching to a **different** run empties the previous one first, or the page renders one run's
   * curve and cards under another run's id for as long as the fetch takes. Re-entering the **same**
   * run empties nothing: blanking a page to redraw the same figures is a flicker, not a refresh.
   *
   * ## Why the state transition is `untracked`
   *
   * Route entry is observed in an `effect`, and an effect refuses signal writes unless whoever
   * created it passed `allowSignalWrites` (Angular's `NG0600`). Left alone, that makes this store
   * unusable on the strength of an option in a component three files away - and the failure is
   * silent from here, because the refusal is raised inside {@link load}'s `async` body and so
   * arrives as an unhandled rejection rather than as an effect that failed. The permission is
   * therefore taken where the write is argued: `open` is a **command**, not a computation, and a
   * command has no business inheriting its caller's reactive contract. Callers still pass the flag -
   * they have their own writes - but nothing here depends on their doing so.
   *
   * @param jobId the job to follow, or null when the caller was never told one (a bookmark, a
   *     reload, or an arrival from the editor's run panel - only the submitter is handed a job id)
   */
  open(runId: Id, jobId: string | null): void {
    untracked(() => {
      if (this._runId() !== runId) {
        this.stopPolling();
        this._job.set(null);
        this._results.set(null);
        this._topological.set([]);
        this._topologicalError.set(null);
        // A different run is a different network until its record says otherwise, and an element
        // scope naming node 12 of the run being left would draw the *new* run's node 12 under the
        // old one's charts. Everything the previous run's picture was made of goes with it, for the
        // reason the results and the criticality table do: a page must not draw one run's numbers
        // under another run's id (FR-22).
        this.clearScope();
        this.clearStructure();
        // And the cursor, for the same reason one line up: period 40 of the run being left is not
        // period 40 of the run being opened, and a run with a shorter horizon has no such period at
        // all. {@link load} re-defaults it once the new run's record says how long it is (FR-22).
        this.clearCursor();
      }
      void this.load(runId);
      if (jobId) {
        this.follow(jobId);
      }
    });
  }

  /** Loads a run. Fetches the criticality suite once the run names its network. */
  async load(runId: Id): Promise<void> {
    this._runId.set(runId);
    this._state.set('loading');
    this._error.set(null);
    try {
      const wire = await firstValueFrom(this.api.get<WireResults>(`/simulations/${runId}`));
      if (this._runId() !== runId) {
        return;
      }
      // The API omits null fields, so absence becomes null here and nothing below has to know
      // (`core/api-nulls.ts`). Without it a baseline run reads as a disruption run, a locally
      // computed run reads as a restored archive result, and a run whose `params_json` could not be
      // read takes the page down on `record.params.seed`.
      const results = normaliseResults(wire);
      this._results.set(results);
      this._state.set('ready');
      // The horizon is only knowable once the record has arrived, so this is where the cursor takes
      // its default - and only for a run it has not been positioned for (FR-22).
      this.defaultCursor(results.run);
      void this.loadTopological(results.run.networkId, runId);
      void this.loadStructure(results.run.networkId, runId);
    } catch (failure: unknown) {
      if (this._runId() !== runId) {
        return;
      }
      this._error.set(problemMessage(failure, 'Could not load this run.'));
      this._state.set('error');
    }
  }

  /** Re-reads the run - the refresh button, and what the poll calls when the job finishes. */
  async refresh(): Promise<void> {
    const runId = this._runId();
    if (runId !== null) {
      await this.load(runId);
    }
  }

  /**
   * Follows a job, if the caller knows its id.
   *
   * Only the submitter is ever told a job id, so this is driven by a `?jobId=` on the URL the
   * launcher navigates to. Without it the dashboard still works - it simply offers a refresh button
   * instead of a live bar, which is the honest degradation rather than an invented poll of the run
   * endpoint on a timer.
   */
  follow(jobId: string): void {
    this.stopPolling();
    this.poll = this.jobs.poll(jobId).subscribe({
      next: (job) => {
        this._job.set(job);
        if (isTerminalJobStatus(job.status)) {
          // The results exist now; the run row's status and its metric rows are both written by the
          // time the job reports DONE (SimulationService writes them before the job returns).
          void this.refresh();
        }
      },
      error: () => {
        // The job has been evicted from the server's in-memory retention window. The run is
        // durable, so drop the bar and let the refresh button carry it.
        this._job.set(null);
      },
    });
  }

  /** Cooperative cancellation of the followed job. */
  async cancel(): Promise<void> {
    const jobId = this._job()?.jobId;
    if (!jobId || this._cancelling()) {
      return;
    }
    this._cancelling.set(true);
    try {
      await firstValueFrom(this.jobs.cancel(jobId));
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not cancel the run.'));
    } finally {
      this._cancelling.set(false);
    }
  }

  /**
   * `DELETE /simulations/{runId}` - discard the run this dashboard is showing (FR-20).
   *
   * The dashboard is where a researcher decides a result is worth keeping, so it is also where they
   * decide it is not. The caller confirms first, with the typed dialog whose wording
   * comes from `core/run-discard.ts` - one module for both surfaces, so the
   * editor and the dashboard cannot ask different questions about the same act.
   *
   * **The store empties itself on success and the page navigates away**, because this route no
   * longer resolves: `GET /simulations/{runId}` is a 404 from here on, and leaving the numbers on
   * screen would show a result that no longer exists. Polling stops with it - a job whose run is
   * gone has nothing left to report.
   *
   * A `RUN_ACTIVE` 409 is reachable here, unlike in the editor's run panel: this page opens on a
   * `QUEUED` or `RUNNING` run quite deliberately. The problem's own sentence names
   * `DELETE /jobs/{jobId}` as the first call, and the page has a Cancel button for exactly that, so
   * the message is surfaced verbatim rather than reworded.
   *
   * @returns true when the run is gone
   */
  async deleteRun(): Promise<boolean> {
    const runId = this._runId();
    if (runId === null || this._deleting()) {
      return false;
    }
    this._deleting.set(true);
    try {
      await firstValueFrom(this.api.delete<void>(`/simulations/${runId}`));
      this.stopPolling();
      this._job.set(null);
      this._results.set(null);
      this._topological.set([]);
      // The miniature, the element charts and the cursor's position all go with the run they
      // described, for the reason the editor drops its report on a discard: a picture of a deleted
      // run is worse than an empty page.
      this.clearScope();
      this.clearStructure();
      this.clearCursor();
      this._runId.set(null);
      this._state.set('idle');
      this._error.set(null);
      return true;
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not delete this run.'));
      return false;
    } finally {
      this._deleting.set(false);
    }
  }

  /**
   * The run's network - the miniature's nodes and links (FR-22).
   *
   * Once per network id. A completed run freezes its network, so nothing this page can
   * observe will change either list while it is open; re-reading them on every poll tick and every
   * Refresh would be two requests spent to be told the same thing. Switching to a run of a *different*
   * network clears the guard through {@link clearStructure}, which is where the invalidation belongs:
   * the id is the cache key, not the run.
   *
   * The two lists are fetched together and fail together, deliberately - a miniature with nodes and
   * no arcs would be a picture of a network that does not exist. Failing leaves the rest of the
   * dashboard untouched: the curve, the cards and the criticality table are what a reader came for.
   */
  private async loadStructure(networkId: Id, forRunId: Id): Promise<void> {
    if (this.structureNetworkId === networkId) {
      return;
    }
    this._structureError.set(null);
    try {
      const [nodes, links] = await Promise.all([
        firstValueFrom(this.api.get<WireNode[]>(`/networks/${networkId}/nodes`)),
        firstValueFrom(this.api.get<WireLink[]>(`/networks/${networkId}/links`)),
      ]);
      if (this._runId() !== forRunId) {
        return;
      }
      this.structureNetworkId = networkId;
      // Through `core/api-nulls.ts`, because both of these carry omitted fields the drawing branches
      // on: `posX`/`posY` decide whether the miniature uses the stored layout or falls back to
      // echelons, and `capacity.value` decides whether an arc reads *uncapped* or *no capacity
      // available*. `undefined` passes every `!== null` test, silently and inverted.
      this._nodes.set(normaliseNodes(nodes));
      this._links.set(normaliseLinks(links));
    } catch (failure: unknown) {
      if (this._runId() !== forRunId) {
        return;
      }
      this.structureNetworkId = null;
      this._nodes.set([]);
      this._links.set([]);
      this._structureError.set(
        problemMessage(failure, 'Could not read this run’s network, so the map is not drawn.'),
      );
    }
  }

  /**
   * `GET /simulations/{runId}/timeseries/elements` - once per run, at the first gesture that needs
   * it: an element scope, or a move of the period cursor (FR-22).
   *
   * See the class note for why it is here and not in {@link load}. Three outcomes, none of them an
   * error banner: a series to draw, a run that recorded none, and a read that failed. The
   * first two settle the run - `available: false` will not become true - and the third leaves
   * {@link elementsRunId} null so the next element click retries it.
   *
   * `elementRequest` is the same guard `PlaybackStore` keeps: a reader who scopes an element, changes
   * their mind and opens another run must not have the first run's series land on top of the second.
   */
  private ensureElementSeries(): void {
    const runId = this._runId();
    if (runId === null || this.elementsRunId === runId || this._elementsLoading()) {
      return;
    }
    const request = ++this.elementRequest;
    this._elementsLoading.set(true);
    this._elementNote.set(null);
    void firstValueFrom(this.api.get<ElementTimeseries>(elementSeriesPath(runId)))
      .then((series) => {
        if (request !== this.elementRequest) {
          return;
        }
        this.elementsRunId = runId;
        this._elementsLoading.set(false);
        this._elementsAvailable.set(series.available === true);
        this._elements.set(series.available ? indexElementSeries(series) : null);
        this._elementNote.set(series.available ? null : ELEMENT_SERIES_UNAVAILABLE);
      })
      .catch(() => {
        if (request !== this.elementRequest) {
          return;
        }
        // Deliberately not remembered: a failed read may succeed, and an element click is the
        // gesture that says the reader still wants it.
        this.elementsRunId = null;
        this._elementsLoading.set(false);
        this._elementsAvailable.set(false);
        this._elements.set(null);
        this._elementNote.set(ELEMENT_SERIES_UNREADABLE);
      });
  }

  /**
   * Put the cursor at the run's end state, once, for a run it has not been positioned for.
   *
   * **The last period, not the first** - see `period-cursor.defaultCursorPeriod`: every scalar on
   * this page is a horizon figure, so a cursor opening at the warm-up would have the page's
   * per-period column describing period 0 while its cards described the whole run.
   *
   * Guarded on the run id rather than on the cursor's value, because "the reader has not moved it"
   * and "the reader moved it back to the end" are indistinguishable from the value alone, and only
   * one of them is a reason to re-default.
   */
  private defaultCursor(run: SimulationRun): void {
    if (this.cursorRunId === run.id) {
      return;
    }
    this.cursorRunId = run.id;
    this._cursor.set(defaultCursorPeriod(run.horizonPeriods));
  }

  /** No run, no position. The next {@link load} defaults it against the run it reads. */
  private clearCursor(): void {
    this.cursorRunId = null;
    this._cursor.set(0);
  }

  /** Back to the network scope, with nothing of the previous run's element detail held. */
  private clearScope(): void {
    this.elementRequest++;
    this.elementsRunId = null;
    this._scope.set(NETWORK_SCOPE);
    this._elements.set(null);
    this._elementsAvailable.set(false);
    this._elementsLoading.set(false);
    this._elementNote.set(null);
  }

  private clearStructure(): void {
    this.structureNetworkId = null;
    this._nodes.set([]);
    this._links.set([]);
    this._structureError.set(null);
  }

  /**
   * The topological suite of the evaluated network - the criticality table's source.
   *
   * Failures are held apart from {@link error}: this is one panel of the dashboard, and a suite that
   * would not compute is no reason to withhold the run's own results.
   */
  private async loadTopological(networkId: Id, forRunId: Id): Promise<void> {
    this._topologicalError.set(null);
    try {
      const response = await firstValueFrom(
        this.api.get<WireTopologicalResponse>(`/networks/${networkId}/metrics/topological`),
      );
      if (this._runId() === forRunId) {
        // Structural metrics are exact and carry no interval, so every one of them arrives with
        // `ciLow`/`ciHigh` absent - and `ci-value` decides it has an interval with `!== null`.
        this._topological.set(normaliseMetrics(response.metrics));
      }
    } catch (failure: unknown) {
      if (this._runId() === forRunId) {
        this._topological.set([]);
        this._topologicalError.set(
          problemMessage(failure, 'Could not compute the per-node criticality for this network.'),
        );
      }
    }
  }

  private stopPolling(): void {
    this.poll?.unsubscribe();
    this.poll = null;
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }
}
