import { Injectable, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { WireTopologicalResponse, normaliseMetrics } from '../../core/api-nulls';
import { ApiService } from '../../core/api.service';
import {
  Id,
  MetricCode,
  MetricResult,
  MetricScope,
  TOPOLOGICAL_METRIC_CODES,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { NetworkEditorStore } from './network-editor.store';

/** Lifecycle of the suite. Mirrors `EditorLoadState`. */
export type MetricsLoadState = 'idle' | 'loading' | 'ready' | 'error';

/** One node's criticality, ready to render as a row of the table. */
export interface CriticalityRow {
  readonly nodeId: Id;
  readonly name: string;
  readonly value: number;
  /** `value` as a fraction of the largest in the network - the bar width, not the metric. */
  readonly share: number;
}

/**
 * The topological metric suite for the network being edited.
 *
 * Backs the metrics side panel and the node-size-by-criticality encoding:
 *
 * > "node size can encode live `NODE_CRITICALITY` so structural weak points are visible while
 * > editing."
 *
 * ## Why this is a store of its own
 *
 * `NetworkEditorStore` owns the network; this owns an *opinion about* the network, computed from it
 * by the server. Keeping them apart means the editor is never blocked on a metric - a suite that
 * fails to compute leaves the canvas fully usable - and the store's error surface stays about
 * editing rather than about analysis. It is provided on the editor route beside the editor store, so
 * both die with the editing session.
 *
 * ## When it recomputes
 *
 * `GET /networks/{id}/metrics/topological` both computes and persists, and
 * `NODE_CRITICALITY` costs one maximum-flow computation per node, so the suite is not something to
 * re-request per keystroke. Three rules govern it:
 *
 * - **Only while something is looking.** {@link setActive} is driven by the panel being open or the
 *   criticality encoding being on. With neither, edits mark the suite stale and nothing is fetched.
 * - **Debounced.** A burst of edits settles first; the number that matters is the one after the
 *   burst, not during it.
 * - **After the canvas is flushed.** Canvas edits are PATCHed on a two-second debounce, so
 *   computing without flushing first would measure the network as it was up to two seconds ago -
 *   and the edit most likely to be in that window is the one the user is watching the metric for.
 *
 * Node moves are excluded upstream: see `NetworkEditorStore.topologyRevision`.
 */
@Injectable()
export class TopologicalMetricsStore implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly editor = inject(NetworkEditorStore);

  private readonly _metrics = signal<readonly MetricResult[]>([]);
  private readonly _state = signal<MetricsLoadState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _computedInMs = signal<number | null>(null);
  private readonly _stale = signal(false);
  private readonly _active = signal(false);
  private readonly _sizeByCriticality = signal(false);

  readonly metrics = this._metrics.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  /** Wall-clock cost the server reported, against FR-04's 2 s budget at 1,000 nodes. */
  readonly computedInMs = this._computedInMs.asReadonly();
  /** True when an edit has landed since the displayed suite was computed. */
  readonly stale = this._stale.asReadonly();
  /** Whether node size encodes `NODE_CRITICALITY` on the canvas. */
  readonly sizeByCriticality = this._sizeByCriticality.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');
  readonly hasMetrics = computed(() => this._metrics().length > 0);

  /** Network-scoped values in suite order - the panel's top section. */
  readonly networkMetrics = computed<readonly MetricResult[]>(() => {
    const scoped = this._metrics().filter((metric) => metric.scope === MetricScope.NETWORK);
    const rank = (code: MetricCode) => {
      const at = TOPOLOGICAL_METRIC_CODES.indexOf(code);
      // An unrecognised code sorts last rather than being dropped: the backend's registry discovers
      // calculators at runtime, so a code this build has never heard of is a new metric to show.
      return at < 0 ? TOPOLOGICAL_METRIC_CODES.length : at;
    };
    return [...scoped].sort((a, b) => rank(a.metricCode) - rank(b.metricCode));
  });

  /** `NODE_CRITICALITY` by node id - what the canvas sizes nodes from. */
  readonly criticality = computed<ReadonlyMap<Id, number>>(() => {
    const byNode = new Map<Id, number>();
    for (const metric of this._metrics()) {
      if (
        metric.metricCode === MetricCode.NODE_CRITICALITY &&
        metric.scope === MetricScope.NODE &&
        metric.scopeId !== null
      ) {
        byNode.set(metric.scopeId, metric.value);
      }
    }
    return byNode;
  });

  /**
   * The criticality table, worst first.
   *
   * The bar is scaled to the largest value in this network rather than to 1. A network whose worst
   * node scores 0.2 is a network with no dominant weak point, and a table of six near-empty bars
   * would say that far less clearly than six bars whose lengths differ.
   */
  readonly criticalityRows = computed<readonly CriticalityRow[]>(() => {
    const rows: { nodeId: Id; name: string; value: number }[] = [];
    for (const metric of this._metrics()) {
      if (
        metric.metricCode !== MetricCode.NODE_CRITICALITY ||
        metric.scope !== MetricScope.NODE ||
        metric.scopeId === null
      ) {
        continue;
      }
      rows.push({
        nodeId: metric.scopeId,
        // The server sends the name from the snapshot it computed on; the store's own copy is the
        // fallback for a node renamed since, and the id is the last resort.
        name:
          this.editor.nodes().get(metric.scopeId)?.name ?? metric.scopeName ?? `#${metric.scopeId}`,
        value: metric.value,
      });
    }
    rows.sort((a, b) => b.value - a.value || a.name.localeCompare(b.name));
    const highest = rows.length ? rows[0].value : 0;
    return rows.map((row) => ({ ...row, share: highest > 0 ? row.value / highest : 0 }));
  });

  /** Which network the displayed suite describes, so a late response can be discarded. */
  private currentNetworkId: Id | null = null;
  /** The `topologyRevision` the displayed suite was computed at. */
  private appliedRevision = 0;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(
      () => {
        const networkId = this.editor.network()?.id ?? null;
        const revision = this.editor.topologyRevision();
        const active = this._active();
        // The reaction reads and writes the store's own signals; tracking those would make it
        // depend on its own output. The three lines above are the whole dependency set.
        untracked(() => this.react(networkId, revision, active));
      },
      { allowSignalWrites: true },
    );
  }

  /**
   * Whether anything is currently reading the suite.
   *
   * Called by the editor with "the panel is open, or node size encodes criticality". Turning it on
   * fetches immediately if the suite is stale; turning it off stops the recomputes without
   * discarding what is already there, so re-opening the panel shows the last figures at once with a
   * stale marker rather than an empty pane.
   */
  setActive(active: boolean): void {
    this._active.set(active);
  }

  /** The encoding toggle. Keeps the suite live even with the panel closed. */
  toggleSizeByCriticality(): void {
    this._sizeByCriticality.update((on) => !on);
  }

  dismissError(): void {
    this._error.set(null);
  }

  /**
   * Recompute now - the panel's refresh button, and the path every automatic refresh takes.
   *
   * Flushes the editor's pending writes first, so the suite describes what is on the canvas rather
   * than what was last sent.
   */
  async refresh(): Promise<void> {
    const networkId = this.currentNetworkId;
    if (networkId === null) {
      return;
    }
    this.cancelTimer();
    const revisionAtStart = this.editor.topologyRevision();
    this._state.set('loading');
    this._error.set(null);
    try {
      await this.editor.flush();
      const response = await firstValueFrom(
        this.api.get<WireTopologicalResponse>(`/networks/${networkId}/metrics/topological`),
      );
      if (this.currentNetworkId !== networkId) {
        // The editor moved to another network - most likely a fork - while this was in flight.
        return;
      }
      // The suite is exact and carries no intervals, so every row arrives with `ciLow`
      // and `ciHigh` omitted rather than null - and `ci-value` reads `!== null` to decide whether
      // it has an interval to draw (`core/api-nulls.ts`).
      this._metrics.set(normaliseMetrics(response.metrics));
      this._computedInMs.set(response.computedInMs);
      this._state.set('ready');
      // Still stale if an edit landed while the server was computing: the answer describes the
      // network as it was when the request went out.
      this._stale.set(this.editor.topologyRevision() !== revisionAtStart);
    } catch (failure: unknown) {
      if (this.currentNetworkId !== networkId) {
        return;
      }
      this._error.set(problemMessage(failure, 'Could not compute the topological metrics.'));
      this._state.set('error');
    }
  }

  // ------------------------------------------------------------------------ internals

  private react(networkId: Id | null, revision: number, active: boolean): void {
    if (networkId === null) {
      this.currentNetworkId = null;
      this.reset();
      return;
    }
    if (networkId !== this.currentNetworkId) {
      this.currentNetworkId = networkId;
      this.appliedRevision = revision;
      this.reset();
      this._stale.set(true);
      if (active) {
        void this.refresh();
      }
      return;
    }
    if (revision !== this.appliedRevision) {
      this.appliedRevision = revision;
      this._stale.set(true);
      if (active) {
        this.schedule();
      }
      return;
    }
    // Nothing changed but somebody started looking: catch up if what is on screen is out of date.
    if (active && this._stale() && this._state() !== 'loading') {
      void this.refresh();
    }
  }

  private reset(): void {
    this.cancelTimer();
    this._metrics.set([]);
    this._computedInMs.set(null);
    this._error.set(null);
    this._state.set('idle');
    this._stale.set(false);
  }

  private schedule(): void {
    this.cancelTimer();
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      void this.refresh();
    }, METRICS_DEBOUNCE_MS);
  }

  private cancelTimer(): void {
    if (this.refreshTimer !== null) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  ngOnDestroy(): void {
    this.cancelTimer();
  }
}

/**
 * How long a burst of edits settles before the suite is recomputed.
 *
 * Longer than the editor's 500 ms time-validation debounce and than its two-second save window is
 * not: the refresh flushes pending writes itself, so it does not need to wait for them. Long enough
 * that drawing three links in a row costs one recompute, not three - which matters here more than
 * anywhere else in the editor, because the suite is the most expensive thing the API computes
 * synchronously (`NODE_CRITICALITY`, FR-04).
 */
const METRICS_DEBOUNCE_MS = 1200;
