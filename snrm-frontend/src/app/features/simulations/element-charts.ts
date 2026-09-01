import { Id, LinkTimeseries, NetworkLink, NodeTimeseries } from '../../core/models';
import {
  SparkFrame,
  SparkSeries,
  cursorX,
  seriesMean,
  valueY,
  weightedMean,
} from '../network-editor/sparkline-geometry';
import { periodTicks } from './curve-geometry';

/**
 * What an element scope draws, as arithmetic (FR-22).
 *
 * > An element scope replaces the curve area with that element's per-period series as full
 * > stepwise charts - on-hand, in-transit, arrivals, served/unserved, throughput, availability and
 * > inbound lead for a node; flow, utilisation and availability for a link - each with its
 * > paired-baseline overlay, under the FR-18 disciplines (nulls break the line, absence is a
 * > sentence, `available: false` degrades to the stated message and never to zeros).
 *
 * Free of Angular and of the DOM, like `curve-geometry.ts` beside it. What is pinned here is not a
 * shape but a set of **readings**: which array a chart draws, what its gaps mean, whether the schema
 * has an undisrupted twin for it, and which mean is the honest one to print beside it. Each of those
 * is a claim about the run, and each is the sort of claim that is wrong silently.
 * `element-charts.spec.ts` pins them against
 * `../snrm-backend/samples/four-echelon-playback/README.md` §6.5, whose figures are
 * derived by hand from the per-period loop rather than from a run.
 *
 * ## The step geometry is the editor's, at chart size
 *
 * `network-editor/sparkline-geometry.ts` already draws a stepwise line whose nulls break it, which is
 * the same line this needs three times as large - so this module derives *what* to draw and
 * `sparkline()` draws it, through the four optional gutters that file grew for the axes (a chart has
 * a value axis and a period axis; a sparkline has neither). A fourth geometry would be a second
 * implementation of "a period is a band, and a gap is nothing", free to disagree with the inspector
 * about the same element of the same run. Three charting geometries are all this application has,
 * and this keeps it at three.
 *
 * ## Only three of the eleven columns have a baseline, and the schema is why
 *
 * `V9__element_timeseries.sql` records `baseline_on_hand`, `baseline_served` and `baseline_flow` and
 * nothing else. So on-hand, served and flow carry the paired overlay FR-22 asks for, and in-transit,
 * arrivals, unserved, throughput, availability and inbound lead are drawn **bare** - never overlaid
 * with a copy of themselves, which would claim the disruption moved no material. This is the rule
 * `network-series.ts` states one scope up for `endingInventory` and `inPipeline`, applied to the
 * element series; the panel prints {@link NO_BASELINE_COLUMN} once beneath the charts rather than
 * eight times.
 *
 * Unserved is the interesting one, and it is deliberately not derived. `demand = served + unserved`
 * holds at a node, and the paired replications share their demand realisations by construction,
 * so `baselineServed + baselineUnserved` would be the same demand and an undisrupted
 * unserved series could be computed as `served + unserved − baselineServed`. That is an inference
 * from an invariant the schema does not state at element scope, and a chart is the wrong place to
 * make one: if it were ever false, nothing on the page would say so. The `UNMET_DEMAND` card and the
 * network-scope curve are where the paired comparison is already made honestly.
 *
 * ## The period cursor reads these same arrays a second time
 *
 * The cursor is a line at {@link cursorX} on a frame these charts already carry, and its at-period
 * figure is {@link readingAt}. Both were exported and uncalled when this module landed; the seam held
 * exactly as it was written, and gaining the cursor cost this file one field -
 * {@link SeriesChart.absentReading}, the three-word form of {@link SeriesChart.absence} for the
 * figure that has no room for a sentence. The whole-horizon chart is still the drawing and the cursor
 * is still a second *reading* of the same arrays, which is what keeps a step from re-deriving any
 * geometry (`features/simulations/period-cursor.ts`, FR-22).
 */

/** How a chart's numbers read, which decides both the axis labels and the printed mean. */
export type ChartUnit =
  /** Units of product - a stock, a flow, an arrival. Written plainly. */
  | 'quantity'
  /** A fraction in [0,1] - availability, utilisation. Written as a percentage. */
  | 'ratio'
  /** A count of this run's periods, which needs the run's clock to be read. */
  | 'periods'
  /**
   * Money, grouped to two decimals and with **no symbol** - the model has no currency (costs are
   * carried as bare doubles). Used by the network scope's cost charts.
   */
  | 'currency';

/**
 * How large a chart is drawn.
 *
 * A page shows many per-period series and a reader is asking one question of one of them at a time,
 * so the set is drawn small and any chart can be enlarged with a double-click. The size is not a
 * cosmetic scale factor: a small chart states its own drawing box in user units, so the axis text
 * stays the same size on screen at either size rather than shrinking with the picture.
 */
export type ChartSize = 'small' | 'large';

/**
 * A chart's identity in the page's expanded set - **scoped**, deliberately.
 *
 * A node's `onHand` and the whole network's `onHand` are two different charts of two different
 * quantities that happen to share a key, and enlarging one must not enlarge the other when the
 * reader changes scope.
 */
export function chartId(scope: 'network' | 'element', key: string): string {
  return `${scope}:${key}`;
}

/**
 * One per-period series as a chart: what it draws, what its absences mean, and which mean is honest.
 *
 * Named for the shape rather than for the scope, because the network scope grew charts of its
 * own (`network-charts.ts`): the two scopes draw the same thing from different series, and one type
 * is what lets `series-chart` stay the single implementation of "a step line with a fitted axis, a
 * cursor and an absence that is a sentence".
 */
export interface SeriesChart {
  readonly key: string;
  readonly label: string;
  /** The series drawn. May carry nulls, which break the line rather than dipping it to zero. */
  readonly values: SparkSeries;
  /** The undisrupted twin to overlay, or **null** where the schema records none. */
  readonly baseline: SparkSeries | null;
  readonly unit: ChartUnit;
  /** One sentence about what the quantity is, or null where the label says it. */
  readonly hint: string | null;
  /**
   * What a **gap** in this line means, or null for a series that cannot have one.
   *
   * Stated per series because the two nullable columns mean two different things, and
   * neither of them means zero: an absent `inboundLead` is *nothing was dispatched toward this node*,
   * an absent `utilisation` is *no capacity was available*.
   */
  readonly absence: string | null;
  /**
   * What the **at-cursor figure** reads where this period has no value, or null for `-`.
   *
   * The short form of {@link absence}, and it exists because the two are read in different places by
   * different lengths: a footnote under a chart can afford a sentence explaining what a gap is, and
   * the figure beside the chart's title has room for three words. Both say the same thing and
   * neither says 0 - *no inbound this period* is the element inspector's own wording for exactly
   * this reading (FR-18), taken verbatim so one node's period 3 does not read two ways on
   * two screens (FR-22).
   */
  readonly absentReading: string | null;
  /**
   * A sentence to show **instead of** the chart, or null to draw it.
   *
   * One case: an arc with no declared capacity has no utilisation series at all - every period is
   * null, and a chart of nothing broken into nothing would be a worse answer than the word
   * *uncapped* (the reading `element-inspector` already makes).
   */
  readonly suppressed: string | null;
  /** The figure printed beside the chart and drawn as its reference line - one number, two readings. */
  readonly mean: number | null;
  /** What that figure is: `mean`, or `dispatch-weighted mean` where a plain one would mislead. */
  readonly meanLabel: string;
}

/** Printed once under a scope's charts, for every chart in it that has no overlay. */
export const NO_BASELINE_COLUMN =
  "Charts without a dashed overlay have no undisrupted twin to draw: the run's element table records "
  + 'a baseline for on-hand, served and flow alone. Drawing one as a copy of itself would claim the '
  + 'disruption moved no material.';

/** What an arc with no declared capacity says in place of its utilisation chart. */
export const UNCAPPED =
  'Uncapped - this arc has no declared capacity, so there is no fraction for it to be at. A 0% here '
  + 'would say it was idle.';

const NO_INBOUND =
  'A gap is a period in which nothing was dispatched toward this node, so there was no lead time to '
  + 'measure. A 0 would say material arrived instantly.';

const NO_CAPACITY =
  'A gap is a period in which no capacity was available - an outage took the ceiling to zero. A dark '
  + 'arc is not an idle one, so it is not drawn as 0%.';

/**
 * The same two absences as at-cursor figures - the element inspector's wording, verbatim.
 *
 * `element-inspector.component.ts` prints these two strings for the same two columns at the playback
 * clock's period (FR-18). The dashboard's cursor asks the identical question of the
 * identical run, so it gets the identical answer rather than a paraphrase of it: *no inbound this
 * period* and *no capacity available* are readings a researcher will carry between the two screens.
 */
const NO_INBOUND_AT = 'no inbound this period';

const NO_CAPACITY_AT = 'no capacity available';

/**
 * The eight charts of a node scope, in the order the dashboard draws them.
 *
 * `served` and `unserved` are drawn as two charts rather than one, because they pair
 * differently: `served` has an undisrupted twin in the schema and `unserved` does not, and a single
 * chart could show at most one of those honestly. They stay adjacent so the pair still reads as one
 * question - what the customer asked for, and what it did not get.
 *
 * @param inboundFlow what each period dispatched toward this node - {@link inboundFlowFor}. Empty is
 *     allowed and gives an absent average lead rather than an unweighted one.
 */
export function nodeCharts(
  series: NodeTimeseries,
  inboundFlow: readonly number[],
): readonly SeriesChart[] {
  return [
    {
      key: 'onHand',
      label: 'On hand',
      values: series.onHand,
      baseline: series.baselineOnHand,
      unit: 'quantity',
      hint: 'Stock held at the end of the period, after the period’s arrivals and its dispatches.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.onHand),
      meanLabel: 'mean',
    },
    {
      key: 'inTransit',
      label: 'In transit',
      values: series.inTransit,
      baseline: null,
      unit: 'quantity',
      hint: 'Inbound pipeline at period end: shipments on the wire plus this node’s processing dwell.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.inTransit),
      meanLabel: 'mean',
    },
    {
      key: 'arrivals',
      label: 'Arrivals',
      values: series.arrivals,
      baseline: null,
      unit: 'quantity',
      hint:
        'What the pipeline delivered here this period - an inbound arc’s flow, one lead time later. '
        + 'Read it beside that arc’s Flow chart: the offset is the mechanic, not a discrepancy.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.arrivals),
      meanLabel: 'mean',
    },
    {
      key: 'served',
      label: 'Served',
      values: series.served,
      baseline: series.baselineServed,
      unit: 'quantity',
      hint: 'Demand met here. Zero everywhere but a CUSTOMER - demand sits on customer nodes alone.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.served),
      meanLabel: 'mean',
    },
    {
      key: 'unserved',
      label: 'Unserved',
      values: series.unserved,
      baseline: null,
      unit: 'quantity',
      hint:
        'Demand wanted and not got. Lost, not backlogged - the engine carries nothing forward, so '
        + 'nothing later in the run makes this up.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.unserved),
      meanLabel: 'mean',
    },
    {
      key: 'throughput',
      label: 'Throughput',
      values: series.throughput,
      baseline: null,
      unit: 'quantity',
      hint:
        'Flow across this node’s own capacity arc - production at a supply origin, pass-through '
        + 'elsewhere. On a network whose legs all have lead times it is 0 at every node but the '
        + 'origins, which is a measurement rather than a gap.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.throughput),
      meanLabel: 'mean',
    },
    {
      key: 'availability',
      label: 'Availability',
      values: series.availability,
      baseline: null,
      unit: 'ratio',
      hint: 'The period’s availability multiplier: scenario events × random outages.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.availability),
      meanLabel: 'mean',
    },
    {
      key: 'inboundLead',
      label: 'Inbound lead',
      values: series.inboundLead,
      baseline: null,
      unit: 'periods',
      hint: 'Dispatch-weighted mean lead of what was sent here, in this run’s periods.',
      absence: NO_INBOUND,
      absentReading: NO_INBOUND_AT,
      suppressed: null,
      // Weighted, for the reason `sparkline-geometry.weightedMean` gives and the element inspector
      // prints: a plain mean gives a period that moved one unit the same say as one that moved a
      // hundred. Two surfaces reading one node must not print two different average lead times.
      mean: weightedMean(series.inboundLead, inboundFlow),
      meanLabel: 'dispatch-weighted mean',
    },
  ];
}

/**
 * The three charts of a link scope, in the order the dashboard draws them.
 *
 * @param capacityDeclared whether the arc has a capacity at all - `link.capacity.value !== null`.
 *     The utilisation column is null in two different situations and only this distinguishes them:
 *     no ceiling to be a fraction of, or a ceiling an outage took to zero.
 */
export function linkCharts(
  series: LinkTimeseries,
  capacityDeclared: boolean,
): readonly SeriesChart[] {
  return [
    {
      key: 'flow',
      label: 'Flow',
      values: series.flow,
      baseline: series.baselineFlow,
      unit: 'quantity',
      hint:
        'Dispatched onto the arc this period - not what arrived. It lands at the target one lead '
        + 'time later, in that node’s Arrivals.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.flow),
      meanLabel: 'mean',
    },
    {
      key: 'utilisation',
      label: 'Utilisation',
      values: series.utilisation,
      baseline: null,
      unit: 'ratio',
      hint: capacityDeclared ? 'Flow over the capacity actually available this period.' : null,
      absence: capacityDeclared ? NO_CAPACITY : null,
      absentReading: capacityDeclared ? NO_CAPACITY_AT : null,
      suppressed: capacityDeclared ? null : UNCAPPED,
      mean: capacityDeclared ? seriesMean(series.utilisation) : null,
      meanLabel: 'mean',
    },
    {
      key: 'availability',
      label: 'Availability',
      values: series.availability,
      baseline: null,
      unit: 'ratio',
      hint: 'The period’s availability multiplier: scenario events × random outages.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.availability),
      meanLabel: 'mean',
    },
  ];
}

/**
 * What each period dispatched toward a node: its inbound arcs' flows, summed.
 *
 * The weights of the inbound lead's mean. `LinkTimeseries.flow` is what left the source in that
 * period and `NodeTimeseries.inboundLead` is the lead of *that* dispatch, so the two align index for
 * index with no shift (the sample's §6.5.4 is the check: `CUST-1`'s lead is null in exactly the
 * period arc **c** carried nothing). This is `ElementInspectorComponent.inboundFlow` lifted into a
 * pure function, so the dashboard and the inspector cannot weight one node's lead two ways.
 *
 * A node with no inbound arc - a supply origin - answers an empty array, which makes its average lead
 * absent rather than zero. That is the structural case, not an edge one.
 */
export function inboundFlowFor(
  nodeId: Id,
  links: readonly Pick<NetworkLink, 'id' | 'targetNodeId'>[],
  series: ReadonlyMap<Id, LinkTimeseries>,
): readonly number[] {
  const totals: number[] = [];
  for (const link of links) {
    if (link.targetNodeId !== nodeId) {
      continue;
    }
    const flow = series.get(link.id)?.flow;
    if (!flow) {
      continue;
    }
    for (let period = 0; period < flow.length; period++) {
      const value = flow[period];
      totals[period] = (totals[period] ?? 0) + (Number.isFinite(value) ? value : 0);
    }
  }
  return totals;
}

// ------------------------------------------------------------------------------- the axes

/** One label on the value axis: what it reads, and where it sits. */
export interface ValueTick {
  readonly value: number;
  readonly y: number;
}

/** One label on the period axis. */
export interface PeriodTick {
  readonly period: number;
  readonly x: number;
}

/**
 * Three labels down the value axis: the maximum, the midpoint and the minimum.
 *
 * Three rather than a round-number ladder, and it is the fitted scale that decides it. A stock or a
 * flow has no meaningful floor or ceiling (`sparkline-geometry`'s module note argues this against the
 * performance curve's pinned [0, 1]), so the axis is whatever this element reached - and the two
 * numbers a reader needs from it are the ends. Inventing "nice" ticks would put labels at values the
 * series never took and imply a scale it does not have.
 *
 * A **flat** series has one tick, at the line: its range is degenerate, so a top and a bottom label
 * showing the same number would read as a chart with no variation *and* a scale, which is one claim
 * too many. `valueY` already draws such a series through the middle.
 */
export function valueTicks(frame: SparkFrame): readonly ValueTick[] {
  if (frame.count === 0) {
    return [];
  }
  if (frame.max <= frame.min) {
    return [{ value: frame.max, y: valueY(frame.max, frame) }];
  }
  const middle = (frame.min + frame.max) / 2;
  return [
    { value: frame.max, y: valueY(frame.max, frame) },
    { value: middle, y: valueY(middle, frame) },
    { value: frame.min, y: valueY(frame.min, frame) },
  ];
}

/**
 * Where the period labels go, at the **centre of each band**.
 *
 * `periodTicks` from `curve-geometry.ts` chooses which periods get a label - whole periods, thinned
 * to about eight - so the element charts and the performance curve tick the same horizon at the same
 * places, and a reader moving between the two scopes is not asked to re-learn the axis. Where they
 * differ is the x: the curve is a line through samples and labels the sample, while a step chart
 * draws a period as a band, so the label sits under the middle of the band it names. That is
 * {@link cursorX}'s own rule, which is what will put the cursor line through its own label when
 * FR-22's period cursor lands.
 */
export function periodTicksFor(frame: SparkFrame, target = 8): readonly PeriodTick[] {
  if (frame.count === 0) {
    return [];
  }
  return periodTicks(frame.count, target).map((period) => ({
    period,
    x: cursorX(period, frame),
  }));
}

/**
 * One period of a series, or **null** where it has none - the at-period figure of FR-22's cursor.
 *
 * Null rather than 0 for an absent entry, and clamped into the series at both ends: this feeds a
 * sentence, where a 0 would be a claim, rather than a style, where `playback-channels.valueAt`'s
 * clamp to 0 is the honest fallback. `series-chart` renders the null through
 * {@link SeriesChart.absentReading} - *no inbound this period*, never a 0.
 */
export function readingAt(values: SparkSeries | undefined, period: number): number | null {
  if (!values?.length) {
    return null;
  }
  const index = Math.min(Math.max(0, Math.floor(period)), values.length - 1);
  const value = values[index];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
