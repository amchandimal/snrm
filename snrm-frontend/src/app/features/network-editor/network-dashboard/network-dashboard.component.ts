import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import {
  MetricCard,
  formatCurrency,
  formatMetricValue,
  formatUnits,
  readablePeriods,
  toMetricCard,
} from '../../../core/metric-display';
import { MetricScope } from '../../../core/models';
import { formatNumber } from '../../../core/time-units';
import { CiValueComponent } from '../../../shared/ci-value/ci-value.component';
import { MetricBadgeComponent } from '../../../shared/metric-badge/metric-badge.component';
import { EditorRunStore } from '../editor-run.store';
import { NetworkEditorStore } from '../network-editor.store';
import { networkSeries, valueAtPeriod } from '../network-series';
import { PlaybackStore } from '../playback.store';
import { SparkBox, Sparkline, cursorX, sparkline } from '../sparkline-geometry';
import { CriticalityRow, TopologicalMetricsStore } from '../topological-metrics.store';

/** One row of the live block: what it says now, and what it did over the run. */
interface LiveRow {
  readonly key: string;
  readonly label: string;
  /** The figure at the playback clock's period, already written the way its quantity reads. */
  readonly value: string;
  /** One sentence under the row, or null. */
  readonly hint: string | null;
  readonly spark: Sparkline;
  /** True where `RUN_TIMESERIES` carries an undisrupted twin for this quantity. */
  readonly hasBaseline: boolean;
}

/** One row of the structure block's topological list. */
interface StructureRow {
  readonly code: string;
  readonly label: string;
  readonly value: string;
  readonly meaning: string;
}

/** How many criticality rows the dashboard shows before deferring to the metrics panel. */
const TOP_CRITICALITY = 3;

/**
 * The network dashboard (FR-19).
 *
 * > "Clicking empty canvas (equivalently, deselecting everything) presents the network dashboard:
 * > live playback figures …, the run's full metric suite including `AVG_INVENTORY` and
 * > `AVG_PIPELINE`, and - with or without a run - the structural suite."
 *
 * ## It is the empty-selection state, not a new gesture
 *
 * Clicking empty canvas already unselects everything, and Escape already does the same
 * (`NetworkEditorStore.select([], [])`). Both land on `selectionKind() === 'none'`, whose
 * branch in the property panel used to say *Nothing selected*. This **is** that branch. Nothing new
 * is bound, no gesture is added, and deselect-only still works by construction, because the
 * dashboard is entirely passive: it reads three stores and writes to exactly one thing, the
 * selection, and only when a criticality row is clicked.
 *
 * The answer to "what is selected?" is now *the network* rather than nothing, which is why the panel
 * keeps a one-line hint at the bottom rather than dropping the old sentence: an empty canvas is a
 * legitimate reading of the whole configuration, and the hint says how to get back to a part of it.
 *
 * ## Three blocks, each gated on what it needs
 *
 * **Live** needs a playing run - {@link PlaybackStore.enabled} - and reads
 * `report.timeseries` at {@link PlaybackStore.currentPeriod}. Nothing is fetched: the curve is
 * already in memory beside the report, so scrubbing at twenty periods a second costs no request,
 * exactly as the element inspector's series do.
 *
 * **Run suite** needs a loaded report and renders the `NETWORK`-scoped rows through the same
 * {@link toMetricCard}, `app-metric-badge` and `app-ci-value` the run panel and the results dashboard
 * use. One mapping, three surfaces - a card that read differently here would be the same run
 * disagreeing with itself. `AVG_INVENTORY` and `AVG_PIPELINE` need no special case: they are two more
 * codes the registry emits, and `METRIC_DESCRIPTORS` is where they are described.
 *
 * **Structure** needs nothing at all, and that is the point - the structural suite is computed on
 * save, so a network can be judged structurally before an hour of Monte Carlo is spent on
 * it. It is the block a researcher sees on a network just drawn.
 *
 * ## Absent renders absent
 *
 * A baseline run omits `TTR`, `LOSS_AREA`, `DISRUPTION_COST_DELTA` and `RESILIENCE_INDEX` (FR-17),
 * and the block says so in a line rather than showing four zeros. The two Stage-2 columns have the
 * mirror-image problem in the live block: `RUN_TIMESERIES` carries no baseline for on-hand or
 * pipeline (`V6__run_timeseries_baseline.sql`), so those two rows are drawn with **no overlay**
 * instead of with a copy of themselves - see `network-series.ts`.
 *
 * ## The suite is live here, and that is a cost taken deliberately
 *
 * `TopologicalMetricsStore` recomputes only while something is reading it, because
 * `NODE_CRITICALITY` is one maximum-flow computation per node (FR-04). The dashboard being
 * on screen counts as a reader, so `NetworkEditorComponent` folds `selectionKind() === 'none'` into
 * `setActive`. The consequence is intended: the figures refresh as the network is edited and carry
 * the store's stale marker while a recompute is in flight, which is what makes them worth reading at
 * all. Deselecting on a thousand-node network therefore costs a recompute - the same cost opening the
 * metrics panel has always had, now reachable by a cheaper gesture.
 */
@Component({
  selector: 'app-network-dashboard',
  standalone: true,
  imports: [CiValueComponent, MetricBadgeComponent],
  templateUrl: './network-dashboard.component.html',
  styleUrl: './network-dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NetworkDashboardComponent {
  private readonly editor = inject(NetworkEditorStore);
  private readonly runs = inject(EditorRunStore);
  readonly playback = inject(PlaybackStore);
  readonly topology = inject(TopologicalMetricsStore);

  /**
   * The drawing box for every live sparkline, in the SVG's own user units.
   *
   * Shallower than the element inspector's 44, because seven of these stack in one panel and each
   * carries a label and a figure of its own. A fixed viewBox scaled by CSS, for the reason
   * `performance-curve` gives: a geometry that depends on a resizable panel's rendered width
   * redraws differently in a screenshot for a paper than it does on screen.
   */
  readonly box: SparkBox = { width: 208, height: 26, pad: 3 };

  readonly viewBox = `0 0 ${this.box.width} ${this.box.height}`;

  // ------------------------------------------------------------------------------ the header

  readonly network = this.editor.network;
  readonly frozen = this.editor.readOnly;

  readonly title = computed(() => this.network()?.name ?? 'Network');

  readonly version = computed(() => {
    const network = this.network();
    return network ? `v${network.version} · #${network.id}` : null;
  });

  // -------------------------------------------------------------------------------- the live

  /** Only while a completed run is playable - the block's whole gate (FR-18). */
  readonly liveVisible = this.playback.enabled;

  readonly period = this.playback.currentPeriod;

  /** `Period 4 - 4 days` on the **run's** clock, never the live network's. */
  readonly clock = computed(() => {
    const periodLength = this.playback.periodLength();
    const period = this.period();
    return periodLength === null
      ? `Period ${period}`
      : `Period ${period} - ${formatUnits(readablePeriods(period, periodLength))}`;
  });

  private readonly series = computed(() => networkSeries(this.runs.report()?.timeseries ?? []));

  /**
   * The seven lines, drawn - recomputed **per report**, not per period.
   *
   * Separate from {@link liveRows} for the reason `ElementInspectorComponent` splits `spark` from
   * `cursorX`: a run playing at twenty periods a second must re-derive the figures and not the
   * geometry, and folding the two together would rebuild seven step-path strings five times a
   * second to draw the same seven lines.
   */
  private readonly liveGeometry = computed<readonly Sparkline[]>(() => {
    const series = this.series();
    if (!series.fill.length) {
      return [];
    }
    return [
      sparkline(series.fill, this.box, series.baselineFill),
      sparkline(series.served, this.box, series.baselineServed),
      sparkline(series.cost, this.box, series.baselineCost),
      sparkline(series.cumulativeCost, this.box, series.cumulativeBaselineCost),
      sparkline(series.cumulativeUnmet, this.box, series.cumulativeBaselineUnmet),
      // No third argument on the last two: `RUN_TIMESERIES` carries no baseline column for on-hand
      // or pipeline, and overlaying them with a copy of themselves would claim the disruption moved
      // no stock (see `network-series.ts`).
      sparkline(series.onHand, this.box),
      sparkline(series.inPipeline, this.box),
    ];
  });

  /**
   * The seven rows of FR-19's live block, in the order they are read.
   *
   * Service first (what the network is for), then money, then what it is holding - the same order
   * the metric suite puts its families in.
   */
  readonly liveRows = computed<readonly LiveRow[]>(() => {
    const series = this.series();
    const geometry = this.liveGeometry();
    if (!geometry.length) {
      return [];
    }
    const at = (values: readonly number[]) => valueAtPeriod(values, this.period());
    const served = at(series.served);
    const demand = at(series.demand);

    const rows: Omit<LiveRow, 'spark'>[] = [
      {
        key: 'fill',
        label: 'Fill rate',
        value:
          demand !== null && demand > 0 && served !== null
            ? formatMetricValue(served / demand, 'ratio')
            : '-',
        hint:
          demand !== null && demand <= 0
            ? 'No demand arose this period, so there is no fraction to be short of.'
            : null,
        hasBaseline: true,
      },
      {
        key: 'served',
        label: 'Served / demand',
        value: `${this.show(served)} of ${this.show(demand)}`,
        hint: null,
        hasBaseline: true,
      },
      {
        key: 'cost',
        label: 'Period cost',
        value: this.money(at(series.cost)),
        hint: 'Fixed, variable, transport, holding and shortage, for this period alone.',
        hasBaseline: true,
      },
      {
        key: 'cumulative-cost',
        label: 'Cumulative cost',
        value: this.money(at(series.cumulativeCost)),
        hint: 'Spent through this period. At the last period it is the run’s TOTAL_COST.',
        hasBaseline: true,
      },
      {
        key: 'unmet',
        label: 'Cumulative unmet units',
        value: this.show(at(series.cumulativeUnmet)),
        hint:
          'Lost, not backlogged - the engine carries no demand forward, so nothing later in the '
          + 'run makes this up.',
        hasBaseline: true,
      },
      {
        key: 'on-hand',
        label: 'Total on-hand',
        value: this.show(at(series.onHand)),
        hint: 'Stock across the whole network at period end. Its horizon mean is AVG_INVENTORY.',
        hasBaseline: false,
      },
      {
        key: 'in-pipeline',
        label: 'In pipeline',
        value: this.show(at(series.inPipeline)),
        hint: 'Material shipped and not yet arrived. Its horizon mean is AVG_PIPELINE.',
        hasBaseline: false,
      },
    ];
    return rows.map((row, index) => ({ ...row, spark: geometry[index] }));
  });

  /**
   * Where the cursor stands, in the shared user-unit box.
   *
   * One number for all seven lines: they share a box, a period count and a slot width, so a cursor
   * per row would be seven copies of one arithmetic - and any that drifted would put the same period
   * in two places on one screen.
   */
  readonly cursorAt = computed<number | null>(() => {
    const first = this.liveGeometry()[0];
    return first && !first.empty ? cursorX(this.period(), first) : null;
  });

  readonly plotTop = computed(() => this.liveGeometry()[0]?.top ?? 0);

  readonly plotBottom = computed(() => {
    const spark = this.liveGeometry()[0];
    return spark ? spark.top + spark.plotHeight : 0;
  });

  // --------------------------------------------------------------------------- the run suite

  readonly hasReport = computed(() => this.runs.report() !== null);

  /**
   * What the **loaded report** applied, not what this session submitted.
   *
   * `reportLabel` rather than `runLabel` since FR-21: the report can belong to a run picked out of
   * the history, which this session never launched and for which the submission label is null. The
   * two agree on a session run, and only this one is defined for the other case.
   */
  readonly runLabel = this.runs.reportLabel;

  readonly reportRun = this.runs.reportRun;

  readonly reportIsBaseline = this.runs.reportIsBaseline;

  /**
   * The `NETWORK`-scoped suite of the loaded run, in the registry's own order.
   *
   * Filtered rather than re-sorted: the API returns the order `MetricCalculatorRegistry` emits,
   * and a second opinion about how a suite reads is the sort of thing that makes two
   * surfaces disagree. `NODE_CRITICALITY`'s per-element rows are what the scope filter removes -
   * they belong to the structure block's table, not to a card.
   */
  readonly suiteCards = computed<readonly MetricCard[]>(() => {
    const period = this.reportRun()?.periodLength ?? null;
    return this.runs
      .reportMetrics()
      .filter((metric) => metric.scope === MetricScope.NETWORK)
      .map((metric) => toMetricCard(metric, period));
  });

  // --------------------------------------------------------------------------- the structure

  readonly structureRows = computed<readonly StructureRow[]>(() =>
    this.topology.networkMetrics().map((metric) => {
      // `describeMetric` through `toMetricCard`, so the label, the sentence and the number's
      // format are the ones every other metric surface uses.
      const card = toMetricCard(metric, null);
      return {
        code: card.code,
        label: card.label,
        value: formatMetricValue(card.value, card.format),
        meaning: card.meaning,
      };
    }),
  );

  /** The worst three nodes. The metrics panel is where the whole table lives. */
  readonly topCriticality = computed<readonly CriticalityRow[]>(() =>
    this.topology.criticalityRows().slice(0, TOP_CRITICALITY),
  );

  readonly moreCriticality = computed(() =>
    Math.max(0, this.topology.criticalityRows().length - TOP_CRITICALITY),
  );

  readonly structureStale = computed(
    () => this.topology.stale() && this.topology.hasMetrics(),
  );

  readonly structureEmpty = computed(
    () => !this.topology.hasMetrics() && !this.topology.isLoading(),
  );

  // -------------------------------------------------------------------------------- actions

  /** Reveal the node a criticality row names - the gesture the metrics panel's table uses. */
  select(row: CriticalityRow): void {
    this.editor.select([row.nodeId], []);
  }

  recomputeStructure(): void {
    void this.topology.refresh();
  }

  percent(value: number): string {
    return `${(value * 100).toFixed(1)}%`;
  }

  // ------------------------------------------------------------------------------ internals

  /** A quantity as the dashboard prints it; `-` where the run has no number for this period. */
  private show(value: number | null): string {
    return value === null ? '-' : formatNumber(value);
  }

  /** Money, through the one formatter - no currency symbol, because the model has no currency. */
  private money(value: number | null): string {
    return value === null ? '-' : formatCurrency(value);
  }
}
