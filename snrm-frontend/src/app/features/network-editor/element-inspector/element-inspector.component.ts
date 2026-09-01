import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import {
  formatMetricValue,
  formatTimeValued,
  formatUnits,
  readablePeriods,
} from '../../../core/metric-display';
import { NetworkLink, NetworkNode, NodeType, RoundingPolicy } from '../../../core/models';
import { durationInPeriods, formatNumber, ratePerPeriod } from '../../../core/time-units';
import { NetworkEditorStore } from '../network-editor.store';
import { PlaybackStore } from '../playback.store';
import {
  SparkBox,
  SparkSeries,
  cumulativeThrough,
  cursorX,
  seriesMean,
  sparkline,
  valueY,
  weightedMean,
} from '../sparkline-geometry';
import { TopologicalMetricsStore } from '../topological-metrics.store';

/**
 * A reading that may be a number or a statement that there is no number.
 *
 * `absent` is what the template greys, and it is the difference between *0% utilised* and *no
 * fraction to be at* - two readings a bare string would let the eye run together.
 */
interface Reading {
  readonly text: string;
  readonly absent: boolean;
}

const ABSENT: Reading = { text: '-', absent: true };

/**
 * The element inspector of visual playback (FR-18).
 *
 * > "selecting an element shows its per-period series in the property panel"
 *
 * The canvas answers *how full, how busy, how dark* for the whole network at once; this answers
 * **how much** for the one element under the pointer, at the period the clock is on. The two are
 * deliberately different questions: `playback-channels.ts` normalises every gauge against that
 * element's own maximum, so two full nodes are not holding the same quantity, and this card is where
 * that quantity is stated.
 *
 * ## It is a card in the property panel, not a fourth panel
 *
 * The panel is already the place a selected element is described, it is always open, and a selection
 * gesture that opened a *second* surface would put the attributes and the readings in different
 * places for the same click. So the card sits above the attribute form inside the same
 * `.editor__panel` aside: structure above, behaviour below, one scroll.
 *
 * It appears only when {@link PlaybackStore.enabled} and exactly one node or one link is selected.
 * A multi-selection gets no card rather than a merged one - an averaged inventory across four nodes
 * is a number the run never produced - and the panel's own bulk-edit behaviour is untouched.
 *
 * ## Every figure is the run's, in the run's own clock
 *
 * The series come from `GET /simulations/{runId}/timeseries/elements`, already in memory
 * (`PlaybackStore.elements`), so scrubbing costs no request. Time-valued readings are formatted
 * through `core/metric-display`'s `formatTimeValued` against the **run's** `periodLength` - never
 * the live network's, which a fork could have changed since. Nothing here re-derives a
 * conversion: periods come from `durationInPeriods`, per-period capacity from `ratePerPeriod`, and
 * ratios from `formatMetricValue`.
 *
 * ## Absent is absent, in three places
 *
 * A null `inboundLead` reads *no inbound this period* and never 0 - a 0 would say material arrived
 * instantly. An arc with no declared capacity reads *uncapped* rather than 0%, and one an
 * outage took to zero available capacity reads *no capacity available* rather than idle. And a run
 * with no element detail at all (recorded before `V9__element_timeseries.sql`, or submitted with
 * `recordElementTimeseries: false`) shows the transport bar's own note in place of the rows, because
 * the clock, the scrub bar and the report beside it all still work.
 *
 * ## Geometry is recomputed per selection, the cursor per period
 *
 * `sparkline()` depends on the selected element and the run; `cursorX()` depends on the clock. They
 * are separate computeds for that reason - a run playing at twenty periods a second re-runs the
 * second and not the first, which is the same rule the canvas effect follows.
 */
@Component({
  selector: 'app-element-inspector',
  standalone: true,
  templateUrl: './element-inspector.component.html',
  styleUrl: './element-inspector.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ElementInspectorComponent {
  private readonly editor = inject(NetworkEditorStore);
  private readonly topology = inject(TopologicalMetricsStore);
  readonly playback = inject(PlaybackStore);

  /**
   * The drawing box, in the SVG's own user units.
   *
   * A fixed viewBox scaled by CSS rather than a measured pixel width, for the reason
   * `performance-curve` gives: a geometry that depends on the rendered width of a panel that can be
   * resized redraws differently in a screenshot for a paper than it does on screen.
   */
  readonly box: SparkBox = { width: 208, height: 44, pad: 4 };

  readonly viewBox = `0 0 ${this.box.width} ${this.box.height}`;

  // ------------------------------------------------------------------------- the subject

  readonly node = computed<NetworkNode | null>(() =>
    this.editor.selectionKind() === 'node' ? (this.editor.selectedNodes()[0] ?? null) : null,
  );

  readonly link = computed<NetworkLink | null>(() =>
    this.editor.selectionKind() === 'link' ? (this.editor.selectedLinks()[0] ?? null) : null,
  );

  /** One element, and a run to read it from. Anything else - including a mix - gets no card. */
  readonly visible = computed(
    () => this.playback.enabled() && (this.node() !== null || this.link() !== null),
  );

  readonly period = this.playback.currentPeriod;

  private readonly periodLength = this.playback.periodLength;

  private readonly nodeSeries = computed(() => {
    const node = this.node();
    return node ? (this.playback.elements()?.nodes.get(node.id)?.series ?? null) : null;
  });

  private readonly linkSeries = computed(() => {
    const link = this.link();
    return link ? (this.playback.elements()?.links.get(link.id)?.series ?? null) : null;
  });

  /** True once there is something to read - the rows render only then. */
  readonly hasSeries = computed(() => this.nodeSeries() !== null || this.linkSeries() !== null);

  /**
   * What the card says instead of rows, or null when it has rows to show.
   *
   * The first branch is the transport bar's own sentence (Stage 4), reused verbatim rather than
   * reworded: two surfaces saying the same thing differently would read as two different problems.
   */
  readonly note = computed<string | null>(() => {
    if (!this.playback.seriesAvailable()) {
      return this.playback.elementNote() ?? 'Reading the element detail of this run…';
    }
    return this.hasSeries() ? null : 'This run recorded no series for the selected element.';
  });

  /** `Period 3 - 3 days`. The run's clock, through `readablePeriods`. */
  readonly clock = computed(() => {
    const periodLength = this.periodLength();
    const period = this.period();
    return periodLength === null
      ? `Period ${period}`
      : `Period ${period} - ${formatUnits(readablePeriods(period, periodLength))}`;
  });

  /**
   * `NODE_CRITICALITY` for the selected node, or null - structure beside behaviour.
   *
   * **Read, never requested.** The suite costs one maximum-flow computation per node and
   * `TopologicalMetricsStore` recomputes only while something is looking at it; making a selection
   * during playback a reason to recompute would put that cost behind a click that asks for something
   * else entirely. A network whose suite has not been computed shows no criticality rather than a
   * zero, and the metrics panel is one button away.
   */
  readonly criticality = computed<string | null>(() => {
    const node = this.node();
    if (!node) {
      return null;
    }
    const value = this.topology.criticality().get(node.id);
    return value === undefined ? null : formatMetricValue(value, 'ratio');
  });

  // ---------------------------------------------------------------------------- the graphic

  /**
   * The stock (node) or flow (link) series as step paths, with its baseline overlaid.
   *
   * On a baseline run the two coincide exactly - `V9__element_timeseries.sql` copies the run's own
   * series into the baseline columns - and that is the correct drawing rather than a defect: the
   * paired set, had it run, would have drawn identically.
   */
  readonly spark = computed(() => {
    const nodeSeries = this.nodeSeries();
    if (nodeSeries) {
      return sparkline(nodeSeries.onHand, this.box, nodeSeries.baselineOnHand);
    }
    const linkSeries = this.linkSeries();
    return linkSeries ? sparkline(linkSeries.flow, this.box, linkSeries.baselineFlow) : null;
  });

  readonly cursorAt = computed<number | null>(() => {
    const spark = this.spark();
    return spark && !spark.empty ? cursorX(this.period(), spark) : null;
  });

  /** The mean the card prints and the line it draws - one number, so the two cannot disagree. */
  readonly average = computed<number | null>(() => {
    const nodeSeries = this.nodeSeries();
    if (nodeSeries) {
      return seriesMean(nodeSeries.onHand);
    }
    const linkSeries = this.linkSeries();
    return linkSeries ? seriesMean(linkSeries.flow) : null;
  });

  readonly averageAt = computed<number | null>(() => {
    const spark = this.spark();
    const average = this.average();
    return spark && !spark.empty && average !== null ? valueY(average, spark) : null;
  });

  readonly plotRight = computed(() => {
    const spark = this.spark();
    return spark ? spark.left + spark.count * spark.slot : 0;
  });

  readonly plotBottom = computed(() => {
    const spark = this.spark();
    return spark ? spark.top + spark.plotHeight : 0;
  });

  /** What the line is drawn against - the scale is fitted to the data, so it is stated. */
  readonly sparkCaption = computed(() => {
    const spark = this.spark();
    if (!spark || spark.empty) {
      return '';
    }
    return `${formatNumber(spark.min)}–${formatNumber(spark.max)} over ${spark.count} periods · dashed: baseline · dotted: average`;
  });

  // -------------------------------------------------------------------------- node readings

  readonly onHandNow = computed(() => this.at(this.nodeSeries()?.onHand));
  readonly inTransitNow = computed(() => this.at(this.nodeSeries()?.inTransit));
  readonly arrivalsNow = computed(() => this.at(this.nodeSeries()?.arrivals));
  readonly throughputNow = computed(() => this.at(this.nodeSeries()?.throughput));

  readonly isCustomer = computed(() => this.node()?.type === NodeType.CUSTOMER);

  readonly servedNow = computed(() => this.at(this.nodeSeries()?.served));
  readonly unservedNow = computed(() => this.at(this.nodeSeries()?.unserved));

  readonly demandNow = computed<number | null>(() => {
    const served = this.servedNow();
    const unserved = this.unservedNow();
    return served === null && unserved === null ? null : (served ?? 0) + (unserved ?? 0);
  });

  /** Served over demand this period, or null where there was no demand to be short of. */
  readonly fillNow = computed<string | null>(() => {
    const demand = this.demandNow();
    const served = this.servedNow();
    return demand !== null && demand > 0 && served !== null
      ? formatMetricValue(served / demand, 'ratio')
      : null;
  });

  readonly stockout = computed(() => (this.unservedNow() ?? 0) > 0);

  /**
   * Demand lost from the start of the run through the period on screen.
   *
   * Cumulative rather than per-period because the engine models **no backlog**: unmet demand is lost
   * rather than carried forward, so nothing later in the run will account for it and a reader
   * watching one period at a time would never see the total.
   */
  readonly lostSoFar = computed(() => {
    const series = this.nodeSeries();
    return series ? cumulativeThrough(series.unserved, this.period()) : 0;
  });

  /** `inboundLead[t]`, dual-formed - or null, which the template renders as words. */
  readonly currentLead = computed<string | null>(() => {
    const series = this.nodeSeries();
    const periodLength = this.periodLength();
    if (!series || periodLength === null) {
      return null;
    }
    const value = this.at(series.inboundLead);
    return value === null ? null : formatTimeValued(value, periodLength);
  });

  /**
   * The dispatch-weighted mean inbound lead over the whole run, dual-formed.
   *
   * Weighted by what each period actually dispatched toward this node, which is the sum of the
   * **flow** series of its inbound arcs - see {@link inboundFlow}. A plain mean of the per-period
   * figures would give a period that moved one unit the same say as one that moved a hundred.
   */
  readonly averageLead = computed<string | null>(() => {
    const series = this.nodeSeries();
    const periodLength = this.periodLength();
    if (!series || periodLength === null) {
      return null;
    }
    const mean = weightedMean(series.inboundLead, this.inboundFlow());
    return mean === null ? null : formatTimeValued(round2(mean), periodLength);
  });

  /**
   * What was dispatched toward this node in each period: its inbound arcs' flows, summed.
   *
   * `LinkTimeseries.flow` is what left the source in that period - the arc lands it at the target
   * one lead time later - and `NodeTimeseries.inboundLead` is the lead of *that* dispatch,
   * so the two align index for index with no shift. The sample's §6.5.4 is the check: `CUST-1`'s
   * lead is null in exactly the period arc **c** carried nothing.
   */
  private readonly inboundFlow = computed<readonly number[]>(() => {
    const node = this.node();
    const elements = this.playback.elements();
    if (!node || !elements) {
      return [];
    }
    const totals: number[] = [];
    for (const link of this.editor.linkList()) {
      if (link.targetNodeId !== node.id) {
        continue;
      }
      const flow = elements.links.get(link.id)?.series.flow;
      if (!flow) {
        continue;
      }
      for (let period = 0; period < flow.length; period++) {
        const value = flow[period];
        totals[period] = (totals[period] ?? 0) + (Number.isFinite(value) ? value : 0);
      }
    }
    return totals;
  });

  /**
   * `throughput / (capacity × availability)`, or the two readings that are not a fraction.
   *
   * A node with no declared capacity is **uncapped**, not at 0%; a node an outage has taken to zero
   * available capacity has no fraction to be at either, and reading it as idle would confuse a dark
   * element with a quiet one (the rule `LinkTimeseries.utilisation` states for arcs, applied here).
   */
  readonly nodeUtilisation = computed<Reading>(() => {
    const node = this.node();
    const series = this.nodeSeries();
    const periodLength = this.periodLength();
    if (!node || !series || periodLength === null) {
      return ABSENT;
    }
    const capacity = ratePerPeriod(node.capacity, periodLength);
    if (capacity === null) {
      return { text: 'uncapped', absent: true };
    }
    const available = capacity * (this.at(series.availability) ?? 0);
    const throughput = this.throughputNow();
    return available > 0 && throughput !== null
      ? { text: formatMetricValue(throughput / available, 'ratio'), absent: false }
      : { text: 'no capacity available', absent: true };
  });

  // -------------------------------------------------------------------------- link readings

  readonly flowNow = computed(() => this.at(this.linkSeries()?.flow));

  /** The engine's own figure, which already carries the two nulls that are not zeros. */
  readonly linkUtilisation = computed<Reading>(() => {
    const link = this.link();
    const series = this.linkSeries();
    if (!link || !series) {
      return ABSENT;
    }
    if (link.capacity.value === null) {
      return { text: 'uncapped', absent: true };
    }
    const value = this.at(series.utilisation);
    return value === null
      ? { text: 'no capacity available', absent: true }
      : { text: formatMetricValue(value, 'ratio'), absent: false };
  });

  /** The declared transit time, on the run's clock - `1 period (1 day)`. */
  readonly declaredLead = computed<string | null>(() => {
    const link = this.link();
    const periodLength = this.periodLength();
    if (!link || periodLength === null) {
      return null;
    }
    const policy: RoundingPolicy = this.editor.network()?.roundingPolicy ?? 'NEAREST';
    return formatTimeValued(
      durationInPeriods(link.leadTime, periodLength, policy),
      periodLength,
    );
  });

  // ----------------------------------------------------------------------- shared readings

  /** `capacity available: 100.0%` - the availability multiplier of this period. */
  readonly availabilityNow = computed<string | null>(() => {
    const series = this.nodeSeries()?.availability ?? this.linkSeries()?.availability;
    const value = this.at(series);
    return value === null ? null : `capacity available: ${formatMetricValue(value, 'ratio')}`;
  });

  readonly averageText = computed<string | null>(() => {
    const average = this.average();
    return average === null ? null : formatNumber(round2(average));
  });

  /** A quantity as the card prints it; `-` where the run has no number for this period. */
  show(value: number | null): string {
    return value === null ? '-' : formatNumber(value);
  }

  // ------------------------------------------------------------------------------ internals

  /**
   * One period of a series, or null where it has none.
   *
   * Clamped into the series like `playback-channels.valueAt`, but answering **null** rather than 0
   * for an absent entry: that function feeds a Cytoscape style, where a number is required and a
   * clamp is the honest fallback, and this one feeds a sentence, where "0" would be a claim.
   */
  private at(values: SparkSeries | undefined): number | null {
    if (!values?.length) {
      return null;
    }
    const index = Math.min(Math.max(0, Math.floor(this.period())), values.length - 1);
    const value = values[index];
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
  }
}

/** Displayed means are 2 dp: `170 / 30` is 5.67 on screen and 5.6666… in the reference line. */
function round2(value: number): number {
  return Number(value.toFixed(2));
}
