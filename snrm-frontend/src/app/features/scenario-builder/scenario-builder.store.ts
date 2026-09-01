import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, forkJoin, tap, throwError } from 'rxjs';

import { ApiService } from '../../core/api.service';
import {
  DisruptionEvent,
  DisruptionEventRequest,
  DisruptionScenario,
  Id,
  Network,
  NetworkLink,
  NetworkNode,
  Region,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { TimelineRow, buildTimeline } from './timeline';

/** Where the open scenario stands. */
export type BuilderState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * One open scenario and the network its timeline is drawn against (FR-05).
 *
 * ## Two ids, and why both are needed
 *
 * The **scenario** is what is being edited and it belongs to a project. The **network** is
 * what its events are read and written against: an event's target is a node id, a link id or a
 * region tag, and its bar is positioned by converting its declared durations onto that network's
 * clock. Neither id implies the other, so the builder holds both and the network is a
 * picker rather than a route parameter - switching it re-lays the same scenario against a different
 * clock, which is the comparison the project-scoping exists to allow.
 *
 * Switching the network never rewrites an event. Only the bars move.
 *
 * ## What is loaded, and when
 *
 * The scenario (with its events) comes from `GET /scenarios/{id}`. The network's nodes, links and
 * region catalogue come with it in one `forkJoin`, because the timeline cannot name a single row
 * without them - a bar labelled "Node #7" is not a chart a researcher can read. Changing the network
 * reloads that trio and leaves the scenario alone.
 *
 * ## Writes
 *
 * Every event write carries `?networkId=`, and every one of them can be refused for a reason the
 * client cannot pre-empt: `EVENT_TARGET_INVALID` if the target does not resolve in that network,
 * `EVENT_EXCEEDS_HORIZON` if the window runs past the end of the run. Both arrive as
 * `problem+json` and are surfaced verbatim - the message names the periods and the horizon, which is
 * the whole of what makes "extend the horizon" actionable.
 *
 * ## What is deliberately not here
 *
 * The REGION preview. `GET /networks/{id}/region-nodes` moved to `core/RegionNodesService` when the
 * network editor gained a disruptions panel of its own (FR-16): two surfaces now resolve
 * the same tag against the same network, and one refusal previewed two different ways is exactly
 * the drift that made the resolution server-side to begin with. `regions` - the tag catalogue the
 * picker offers - stays, because it belongs to the network this timeline is laid against.
 */
@Injectable({ providedIn: 'root' })
export class ScenarioBuilderStore {
  private readonly api = inject(ApiService);

  private readonly _scenario = signal<DisruptionScenario | null>(null);
  private readonly _events = signal<readonly DisruptionEvent[]>([]);
  private readonly _network = signal<Network | null>(null);
  private readonly _nodes = signal<readonly NetworkNode[]>([]);
  private readonly _links = signal<readonly NetworkLink[]>([]);
  private readonly _regions = signal<readonly Region[]>([]);

  private readonly _state = signal<BuilderState>('idle');
  private readonly _networkState = signal<BuilderState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _saving = signal(false);
  private readonly _deletingEvent = signal<Id | null>(null);

  readonly scenario = this._scenario.asReadonly();
  readonly events = this._events.asReadonly();
  readonly network = this._network.asReadonly();
  readonly nodes = this._nodes.asReadonly();
  readonly links = this._links.asReadonly();
  readonly regions = this._regions.asReadonly();

  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  /** True while an event create or replace is in flight. */
  readonly saving = this._saving.asReadonly();
  readonly deletingEvent = this._deletingEvent.asReadonly();

  readonly isLoading = computed(
    () => this._state() === 'loading' || this._networkState() === 'loading',
  );

  /** True once there is both a scenario and a network to draw it against. */
  readonly ready = computed(() => this._scenario() !== null && this._network() !== null);

  /**
   * How many events the open scenario now has.
   *
   * From the loaded events rather than from the scenario's own `eventCount`, which was true when the
   * scenario was fetched and stops being true the moment a bar is added.
   */
  readonly eventCount = computed(() => this._events().length);

  /**
   * The chart: rows grouped by target, bars placed on the selected network's clock.
   *
   * A `computed`, so changing the network, adding an event or renaming a node re-lays the timeline
   * with no explicit refresh anywhere. The arithmetic is `timeline.ts`, which knows nothing about
   * signals or HTTP.
   */
  readonly rows = computed<readonly TimelineRow[]>(() => {
    const network = this._network();
    if (!network) {
      return [];
    }
    return buildTimeline(this._events(), network, {
      nodes: this._nodes(),
      links: this._links(),
      regions: this._regions(),
    });
  });

  /** How many bars run past the end of the run on the currently selected network. */
  readonly barsPastHorizon = computed(
    () => this.rows().flatMap((row) => row.bars).filter((bar) => bar.exceedsHorizon).length,
  );

  /** Targets whose node, link or region is not in the selected network - see `TimelineTarget`. */
  readonly unresolvedTargets = computed(
    () => this.rows().filter((row) => row.target.unresolved).length,
  );

  dismissError(): void {
    this._error.set(null);
  }

  /** Clears everything, for leaving the builder or opening a different project. */
  reset(): void {
    this._scenario.set(null);
    this._events.set([]);
    this._network.set(null);
    this._nodes.set([]);
    this._links.set([]);
    this._regions.set([]);
    this._state.set('idle');
    this._networkState.set('idle');
    this._error.set(null);
  }

  /** `GET /scenarios/{id}` - the scenario and its events, in timeline order. */
  loadScenario(scenarioId: Id): void {
    this._state.set('loading');
    this.api.get<DisruptionScenario>(`/scenarios/${scenarioId}`).subscribe({
      next: (scenario) => {
        this._scenario.set(scenario);
        this._events.set(scenario.events ?? []);
        this._error.set(null);
        this._state.set('ready');
      },
      error: (failure: unknown) => {
        this._scenario.set(null);
        this._events.set([]);
        this._error.set(problemMessage(failure, 'Could not load this scenario.'));
        this._state.set('error');
      },
    });
  }

  /**
   * Selects the network the timeline is drawn against, and loads what it takes to name a row.
   *
   * The network itself carries the clock - `periodLength`, `roundingPolicy`, `horizonPeriods` - and
   * without it no bar has a position. The nodes, links and regions are what turn a `targetId` into a
   * name.
   */
  selectNetwork(networkId: Id): void {
    this._networkState.set('loading');
    forkJoin({
      network: this.api.get<Network>(`/networks/${networkId}`),
      nodes: this.api.get<NetworkNode[]>(`/networks/${networkId}/nodes`),
      links: this.api.get<NetworkLink[]>(`/networks/${networkId}/links`),
      regions: this.api.get<Region[]>(`/networks/${networkId}/regions`),
    }).subscribe({
      next: ({ network, nodes, links, regions }) => {
        this._network.set(network);
        this._nodes.set(nodes);
        this._links.set(links);
        this._regions.set(regions);
        this._error.set(null);
        this._networkState.set('ready');
      },
      error: (failure: unknown) => {
        this._network.set(null);
        this._nodes.set([]);
        this._links.set([]);
        this._regions.set([]);
        this._error.set(problemMessage(failure, 'Could not load the network for this timeline.'));
        this._networkState.set('error');
      },
    });
  }

  /** `POST /scenarios/{id}/events?networkId=…`. */
  createEvent(request: DisruptionEventRequest): Observable<DisruptionEvent> {
    const scenarioId = this._scenario()?.id;
    const networkId = this._network()?.id;
    if (!scenarioId || !networkId) {
      return throwError(() => new Error('Open a scenario and pick a network first.'));
    }
    this._saving.set(true);
    return this.api
      .post<DisruptionEvent>(`/scenarios/${scenarioId}/events`, request, {
        params: { networkId },
      })
      .pipe(
        tap((event) => this.upsertEvent(event)),
        catchError((failure: unknown) => this.failWrite(failure, 'Could not add the event.')),
        finalize(() => this._saving.set(false)),
      );
  }

  /**
   * `PUT /events/{id}?networkId=…` - a full replacement.
   *
   * The whole event goes back, not a patch: the timeline holds the bar it is editing, and a partial
   * write would have the server checking a window half of which came from the request and half from
   * the row.
   */
  replaceEvent(eventId: Id, request: DisruptionEventRequest): Observable<DisruptionEvent> {
    const networkId = this._network()?.id;
    if (!networkId) {
      return throwError(() => new Error('Pick a network first.'));
    }
    this._saving.set(true);
    return this.api
      .put<DisruptionEvent>(`/events/${eventId}`, request, { params: { networkId } })
      .pipe(
        tap((event) => this.upsertEvent(event)),
        catchError((failure: unknown) => this.failWrite(failure, 'Could not save the event.')),
        finalize(() => this._saving.set(false)),
      );
  }

  /** `DELETE /events/{id}` - no network needed; removing a bar resolves nothing. */
  deleteEvent(eventId: Id): Observable<void> {
    this._deletingEvent.set(eventId);
    return this.api.delete<void>(`/events/${eventId}`).pipe(
      tap(() => {
        this._events.update((list) => list.filter((event) => event.id !== eventId));
        this._error.set(null);
      }),
      catchError((failure: unknown) => this.failWrite(failure, 'Could not delete the event.')),
      finalize(() => this._deletingEvent.set(null)),
    );
  }

  private upsertEvent(event: DisruptionEvent): void {
    this._events.update((list) => {
      const without = list.filter((existing) => existing.id !== event.id);
      return [...without, event];
    });
    this._error.set(null);
  }

  private failWrite(failure: unknown, fallback: string): Observable<never> {
    this._error.set(problemMessage(failure, fallback));
    return throwError(() => failure);
  }
}
