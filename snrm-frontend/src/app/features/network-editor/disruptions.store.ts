import { Injectable, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import {
  DisruptionEvent,
  DisruptionEventRequest,
  DisruptionScenario,
  Id,
  Region,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { RegionNodesService } from '../../core/region-nodes.service';
import { ScenariosStore } from '../scenario-builder/scenarios.store';
import { TimelineRow, buildTimeline } from '../scenario-builder/timeline';
import { DisruptionOverlay, buildOverlay } from './disruption-overlay';
import { NetworkEditorStore } from './network-editor.store';

/** Where the open scenario stands. Mirrors `EditorLoadState` and `MetricsLoadState`. */
export type DisruptionsLoadState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The disruption scenario being authored against the network in the editor: disruptions on the
 * canvas (FR-16).
 *
 * > "A disruptions panel beside the property and metrics panels selects or creates a scenario in the
 * > project … and lists that scenario's events which resolve against *this* network."
 *
 * ## The rule that governs everything here
 *
 * **Authoring an event does not modify the network.** A scenario is a different aggregate,
 * so nothing in this store goes near {@link EditorPersistenceService}: no event write enters
 * the debounced canvas-save queue, none of them can trip the immutability guard, and none of them
 * raises the fork prompt. There is deliberately **no** `readOnly()` check anywhere below. A network
 * frozen by a completed run can still have scenarios written against it - and that is the common
 * case, since a frozen network is exactly one a researcher has already evaluated and now wants to
 * ask a new question of.
 *
 * The one thing it does reach into the editor for is {@link NetworkEditorStore.flush}, before
 * resolving a region tag. That flushes the *editor's* pending writes, not this store's - a node
 * retagged a second ago is still in that queue, and asking the server which nodes carry `EU-West`
 * before it lands would resolve the tag the server still holds. Reading after a flush is what
 * `TopologicalMetricsStore` does for the same reason.
 *
 * ## Why it is a store of its own, and session-scoped
 *
 * `NetworkEditorStore` owns the network; this owns a *story told about* the network. Keeping them
 * apart is what makes the paragraph above structural rather than a convention someone has to
 * remember. It is provided on the editor route beside the editor store, so the selected scenario
 * dies with the editing session - but the scenario itself, and the events written into it, belong to
 * the project and outlive every network in it.
 *
 * The project's scenario **list** is not held here: it is `ScenariosStore`, the same root-scoped list
 * the scenario builder's sidebar renders. A scenario created from the canvas has to appear on that
 * page without a refresh, and two lists of one project's scenarios is one list too many.
 *
 * ## Regions
 *
 * A REGION event names a `node.region` tag and nothing on the event says which nodes that is.
 * The halo those nodes carry therefore comes from `GET /networks/{id}/region-nodes`,
 * never from filtering the loaded nodes: a client-side filter would be a second implementation of
 * the resolution, free to disagree with the one a simulation run will use. What *is* computed
 * client-side is when to **re-ask** - see {@link regionFingerprint}, which is a cache key and not an
 * answer.
 */
@Injectable()
export class DisruptionsStore implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly regionNodes = inject(RegionNodesService);
  private readonly editor = inject(NetworkEditorStore);
  /** The project's scenario list - shared with the scenario builder's sidebar. */
  readonly scenarios = inject(ScenariosStore);

  private readonly _scenarioId = signal<Id | null>(null);
  private readonly _events = signal<readonly DisruptionEvent[]>([]);
  /** The tag catalogue of this network - what the region picker offers. */
  private readonly _regions = signal<readonly Region[]>([]);
  /** `region` → node ids, as the server resolved them. See the class note. */
  private readonly _regionNodes = signal<ReadonlyMap<string, readonly Id[]>>(new Map());
  /** Nodes the region tag currently being *typed* resolves to - the live preview. */
  private readonly _draftRegionNodes = signal<readonly Id[]>([]);

  private readonly _state = signal<DisruptionsLoadState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _saving = signal(false);
  private readonly _deletingEvent = signal<Id | null>(null);
  private readonly _active = signal(false);

  readonly events = this._events.asReadonly();
  readonly regions = this._regions.asReadonly();
  /** What the server resolved each tag in play to - read by the panel to reveal a region's nodes. */
  readonly regionMembers = this._regionNodes.asReadonly();
  /**
   * Nodes to light up while a region tag is being chosen (FR-16).
   *
   * > "Region events are authored from the panel … and the nodes a tag resolves to highlight as it
   * > is chosen."
   *
   * Separate from {@link overlay}, and on a separate channel on the canvas, because it answers a
   * different question: the overlay says what *is* disrupted, this says what *would be*. Merging them
   * would show an unsaved draft as an event of the scenario.
   */
  readonly draftRegionNodes = this._draftRegionNodes.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  /** True while an event create or replace is in flight. */
  readonly saving = this._saving.asReadonly();
  readonly deletingEvent = this._deletingEvent.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');

  /** The scenario the panel is authoring into, or null until one is picked. */
  readonly scenario = computed<DisruptionScenario | null>(() => {
    const id = this._scenarioId();
    return id === null
      ? null
      : (this.scenarios.scenarios().find((candidate) => candidate.id === id) ?? null);
  });

  readonly hasScenario = computed(() => this._scenarioId() !== null);

  /**
   * The scenario's events grouped by target, exactly as the timeline groups them.
   *
   * `buildTimeline` rather than a grouping of this panel's own: the list here and the Gantt chart in
   * the scenario builder are two renderings of one arrangement, and a row that reads "PLANT-1" in
   * one place and "Node #12" in the other would be the drift FR-16 exists to close.
   */
  readonly rows = computed<readonly TimelineRow[]>(() => {
    const network = this.editor.network();
    if (!network) {
      return [];
    }
    return buildTimeline(this._events(), network, {
      nodes: this.editor.nodeList(),
      links: this.editor.linkList(),
      regions: this._regions(),
    });
  });

  /** The rows the panel lists: the events that resolve against *this* network. */
  readonly resolvedRows = computed(() => this.rows().filter((row) => !row.target.unresolved));

  /**
   * How many of the scenario's events strike nothing here.
   *
   * Counted rather than hidden. A scenario belongs to the project and legitimately outlives the
   * network its events were written against, so the panel says how many it is not showing
   * - a list shorter than the scenario's own event count, with nothing to explain it, reads as a
   * defect.
   */
  readonly unresolvedEventCount = computed(() =>
    this.rows()
      .filter((row) => row.target.unresolved)
      .reduce((total, row) => total + row.bars.length, 0),
  );

  /** Windows that end after this network's horizon, which the API refuses to store. */
  readonly barsPastHorizon = computed(
    () => this.rows().flatMap((row) => row.bars).filter((bar) => bar.exceedsHorizon).length,
  );

  /**
   * What the canvas draws: a mark per targeted node and link (FR-16).
   *
   * A `computed`, so adding an event, deleting one, switching scenario or renaming a node re-derives
   * the overlay with no explicit refresh anywhere. The arithmetic is `disruption-overlay.ts`, which
   * knows nothing about signals or HTTP.
   */
  readonly overlay = computed<DisruptionOverlay>(() => {
    const network = this.editor.network();
    if (!network) {
      return EMPTY_OVERLAY;
    }
    return buildOverlay(this._events(), network, {
      nodeIds: new Set(this.editor.nodes().keys()),
      linkIds: new Set(this.editor.links().keys()),
      regionNodes: this._regionNodes(),
    });
  });

  /** Distinct region tags the scenario's events name, in a stable order. */
  private readonly regionTags = computed<readonly string[]>(() => {
    const tags = new Set<string>();
    for (const event of this._events()) {
      if (event.targetRegion) {
        tags.add(event.targetRegion);
      }
    }
    return [...tags].sort();
  });

  /**
   * A fingerprint of every node's region tag - the cache key for the resolution, not the resolution.
   *
   * Retagging a node changes which nodes `EU-West` covers, and the server is the only thing that may
   * answer that. But the *question* of whether to ask again is one the client can see for itself,
   * and this is that question: when the tags on the canvas change, re-ask. Deriving it costs nothing
   * while no event names a region, which is the usual case.
   */
  private readonly regionFingerprint = computed(() => {
    if (!this.regionTags().length) {
      return '';
    }
    return this.editor
      .nodeList()
      .map((node) => `${node.id}:${node.region ?? ''}`)
      .sort()
      .join('|');
  });

  /** Which network the loaded events and resolutions describe, so a late response can be discarded. */
  private currentNetworkId: Id | null = null;
  private loadedListForProject: Id | null = null;
  private loadedRegionsFor: Id | null = null;
  private resolvedKey: string | null = null;
  private regionTimer: ReturnType<typeof setTimeout> | null = null;
  private regionSeq = 0;

  constructor() {
    // A fork opens a different network in the same editing session, and everything below is about
    // one network: the resolutions, the rows, the overlay.
    effect(
      () => {
        const networkId = this.editor.network()?.id ?? null;
        const projectId = this.editor.network()?.projectId ?? null;
        const active = this._active();
        untracked(() => this.react(networkId, projectId, active));
      },
      { allowSignalWrites: true },
    );

    // Re-resolve when the set of region tags in play changes, or when the tags on the canvas do.
    effect(
      () => {
        const tags = this.regionTags();
        const fingerprint = this.regionFingerprint();
        untracked(() => this.onRegionInputsChanged(tags, fingerprint));
      },
      { allowSignalWrites: true },
    );
  }

  /**
   * Whether the panel is open.
   *
   * The scenario list and the tag catalogue are fetched on the first activation rather than when the
   * editor loads: a researcher who never opens the panel should not pay two requests for it. Once
   * something is selected the overlay stays on the canvas with the panel closed - the halos are what
   * the scenario *is*, and hiding them behind an open panel would make them a mode rather than a
   * property of the network on screen.
   */
  setActive(active: boolean): void {
    this._active.set(active);
  }

  dismissError(): void {
    this._error.set(null);
  }

  /** The event editor's region preview, on its way to the canvas. Empty clears the highlight. */
  setDraftRegionNodes(nodeIds: readonly Id[]): void {
    this._draftRegionNodes.set(nodeIds);
  }

  // ------------------------------------------------------------------------ scenarios

  /** Opens a scenario for authoring, or clears the selection with null. */
  async selectScenario(scenarioId: Id | null): Promise<void> {
    this._scenarioId.set(scenarioId);
    this._events.set([]);
    this._regionNodes.set(new Map());
    this._draftRegionNodes.set([]);
    this.resolvedKey = null;
    if (scenarioId === null) {
      this._state.set('idle');
      this._error.set(null);
      return;
    }
    await this.loadEvents(scenarioId);
  }

  /**
   * `POST /projects/{id}/scenarios`, then opens it - the "or creates" half.
   *
   * Through `ScenariosStore` so the new scenario appears on the scenario-list page too. A duplicate
   * name comes back 409 `DUPLICATE_NAME` and is surfaced verbatim.
   *
   * @returns the created scenario, or null if the write failed
   */
  async createScenario(name: string): Promise<DisruptionScenario | null> {
    const projectId = this.editor.network()?.projectId;
    if (!projectId) {
      return null;
    }
    try {
      const scenario = await firstValueFrom(this.scenarios.create(projectId, { name }));
      this._error.set(null);
      await this.selectScenario(scenario.id);
      return scenario;
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not create the scenario.'));
      return null;
    }
  }

  // --------------------------------------------------------------------------- events

  /**
   * `POST /scenarios/{id}/events?networkId=…`, once per request.
   *
   * Sequential rather than parallel. These are the several targets of one aimed draft, and
   * a refusal that applies to the draft applies to all of them - `EVENT_EXCEEDS_HORIZON` is a fact
   * about the window, not about the target. Stopping at the first failure leaves the researcher with
   * what succeeded and one message about why the rest did not, instead of the same message five
   * times.
   *
   * @returns true when every request was accepted
   */
  async createEvents(requests: readonly DisruptionEventRequest[]): Promise<boolean> {
    const scenarioId = this._scenarioId();
    const networkId = this.editor.network()?.id;
    if (scenarioId === null || !networkId || !requests.length) {
      return false;
    }
    this._saving.set(true);
    try {
      for (const request of requests) {
        const event = await firstValueFrom(
          this.api.post<DisruptionEvent>(`/scenarios/${scenarioId}/events`, request, {
            params: { networkId },
          }),
        );
        this.upsertEvent(event);
      }
      this._error.set(null);
      return true;
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not add the disruption.'));
      return false;
    } finally {
      this._saving.set(false);
      this.noteEventCount();
    }
  }

  /** `PUT /events/{id}?networkId=…` - a full replacement, as the timeline's editor sends. */
  async replaceEvent(eventId: Id, request: DisruptionEventRequest): Promise<boolean> {
    const networkId = this.editor.network()?.id;
    if (!networkId) {
      return false;
    }
    this._saving.set(true);
    try {
      const event = await firstValueFrom(
        this.api.put<DisruptionEvent>(`/events/${eventId}`, request, { params: { networkId } }),
      );
      this.upsertEvent(event);
      this._error.set(null);
      return true;
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not save the disruption.'));
      return false;
    } finally {
      this._saving.set(false);
    }
  }

  /** `DELETE /events/{id}` - no network needed; removing an event resolves nothing. */
  async deleteEvent(eventId: Id): Promise<boolean> {
    this._deletingEvent.set(eventId);
    try {
      await firstValueFrom(this.api.delete<void>(`/events/${eventId}`));
      this._events.update((list) => list.filter((event) => event.id !== eventId));
      this._error.set(null);
      return true;
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not delete the disruption.'));
      return false;
    } finally {
      this._deletingEvent.set(null);
      this.noteEventCount();
    }
  }

  // ------------------------------------------------------------------------ internals

  private react(networkId: Id | null, projectId: Id | null, active: boolean): void {
    if (networkId !== this.currentNetworkId) {
      this.currentNetworkId = networkId;
      // The events belong to the project and survive; what does not is their resolution against a
      // network that is no longer the one on screen.
      this.cancelRegionTimer();
      this._regionNodes.set(new Map());
      this._draftRegionNodes.set([]);
      this._regions.set([]);
      this.resolvedKey = null;
      this.loadedListForProject = null;
      this.loadedRegionsFor = null;
      // A scenario is project-scoped, so a fork within the project keeps it open - that is the point
      // of that scoping, and re-picking it after every fork would be the friction FR-16 removes.
      const openScenarioId = this._scenarioId();
      if (networkId !== null && openScenarioId !== null) {
        void this.loadEvents(openScenarioId);
      }
    }
    if (!active || networkId === null || projectId === null) {
      return;
    }
    if (this.loadedListForProject !== projectId) {
      this.loadedListForProject = projectId;
      this.scenarios.load(projectId);
    }
    // Once per network, not "whenever the catalogue is empty": a network where nobody has typed a
    // region tag legitimately answers with `[]`, and re-asking on every panel toggle would make the
    // commonest case the most expensive one.
    if (this.loadedRegionsFor !== networkId) {
      this.loadedRegionsFor = networkId;
      void this.loadRegions(networkId);
    }
  }

  /** `GET /scenarios/{id}` - the scenario with its events, earliest start first. */
  private async loadEvents(scenarioId: Id): Promise<void> {
    this._state.set('loading');
    try {
      const scenario = await firstValueFrom(
        this.api.get<DisruptionScenario>(`/scenarios/${scenarioId}`),
      );
      if (this._scenarioId() !== scenarioId) {
        return;
      }
      this._events.set(scenario.events ?? []);
      this._error.set(null);
      this._state.set('ready');
    } catch (failure: unknown) {
      if (this._scenarioId() !== scenarioId) {
        return;
      }
      this._events.set([]);
      this._error.set(problemMessage(failure, 'Could not load this scenario.'));
      this._state.set('error');
    }
  }

  /** `GET /networks/{id}/regions` - the tags in use, for the picker and the row labels. */
  private async loadRegions(networkId: Id): Promise<void> {
    try {
      const regions = await firstValueFrom(
        this.api.get<Region[]>(`/networks/${networkId}/regions`),
      );
      if (this.currentNetworkId === networkId) {
        this._regions.set(regions);
      }
    } catch {
      // A tag catalogue is a convenience: the region field is free text and the event editor's own
      // preview is the authority on what a tag hits. Failing it must not take the panel down - but
      // it does clear the once-per-network guard, so re-opening the panel tries again.
      this._regions.set([]);
      if (this.loadedRegionsFor === networkId) {
        this.loadedRegionsFor = null;
      }
    }
  }

  private onRegionInputsChanged(tags: readonly string[], fingerprint: string): void {
    // Joined on a character a tag cannot contain: a region is free text a researcher typed onto a
    // node, so `['EU West']` and `['EU', 'West']` are two different sets and must not share a key.
    const key = `${tags.join(REGION_KEY_SEPARATOR)}#${fingerprint}`;
    if (key === this.resolvedKey) {
      return;
    }
    this.resolvedKey = key;
    this.cancelRegionTimer();
    if (!tags.length) {
      this._regionNodes.set(new Map());
      return;
    }
    this.regionTimer = setTimeout(() => {
      this.regionTimer = null;
      void this.resolveRegions();
    }, REGION_DEBOUNCE_MS);
  }

  /**
   * Asks the server what every tag in play resolves to, one request per distinct tag.
   *
   * A tag whose request fails is left **out** of the map rather than mapped to an empty array: the
   * two mean different things to the overlay - "not answered" against "answered, nothing carries it"
   * - and drawing no halo for the first is honest, while reporting it as an event that strikes
   * nothing would not be.
   */
  private async resolveRegions(): Promise<void> {
    const networkId = this.editor.network()?.id;
    const tags = this.regionTags();
    if (!networkId || !tags.length) {
      return;
    }
    const seq = ++this.regionSeq;
    // See the class note: this flushes the *editor's* pending canvas writes, so a region typed into
    // the property panel two seconds ago is on the server before we ask what it covers.
    await this.editor.flush();
    const answered = await Promise.all(
      // The return type is stated, not inferred: `as const` would type the node list as the mutable
      // `Id[]` that `map` returns, `isAnswered` narrows to the readonly form, and with the guard's
      // target no longer extending the element type TypeScript falls back to the non-narrowing
      // `filter` overload - leaving `null` in the array the Map constructor sees.
      tags.map(async (tag): Promise<readonly [string, readonly Id[]] | null> => {
        try {
          const resolved = await firstValueFrom(this.regionNodes.resolve(networkId, tag));
          return [tag, resolved.nodes.map((node) => node.id)];
        } catch {
          return null;
        }
      }),
    );
    if (seq !== this.regionSeq || this.editor.network()?.id !== networkId) {
      return;
    }
    this._regionNodes.set(new Map(answered.filter(isAnswered)));
  }

  private upsertEvent(event: DisruptionEvent): void {
    this._events.update((list) => [...list.filter((existing) => existing.id !== event.id), event]);
  }

  /** Keeps the shared scenario list's count honest without refetching it. */
  private noteEventCount(): void {
    const scenarioId = this._scenarioId();
    if (scenarioId !== null) {
      this.scenarios.noteEventCount(scenarioId, this._events().length);
    }
  }

  private cancelRegionTimer(): void {
    if (this.regionTimer !== null) {
      clearTimeout(this.regionTimer);
      this.regionTimer = null;
    }
  }

  ngOnDestroy(): void {
    this.cancelRegionTimer();
    // Nothing else to tear down: the events and the scenario live on the server, and the list this
    // shares with the scenario builder is not ours to clear.
  }
}

function isAnswered(
  entry: readonly [string, readonly Id[]] | null,
): entry is readonly [string, readonly Id[]] {
  return entry !== null;
}

const EMPTY_OVERLAY: DisruptionOverlay = {
  nodes: new Map(),
  links: new Map(),
  unresolved: [],
};

/**
 * How long a burst of region retagging settles before the resolution is re-asked.
 *
 * Longer than a keystroke and shorter than the topological suite's 1200 ms: this is one indexed
 * query per tag rather than a maximum flow per node, and the halo it moves is on screen while the
 * user is typing the tag that moves it.
 */
const REGION_DEBOUNCE_MS = 600;

/** Separator for the region cache key - a unit separator, which no typed tag holds. */
const REGION_KEY_SEPARATOR = '\u001F';
