import { NetworkSeries } from '../network-editor/network-series';
import { seriesMean } from '../network-editor/sparkline-geometry';
import { SeriesChart } from './element-charts';

/**
 * What the **network scope** draws besides its fill-rate curve, as arithmetic.
 *
 * > "The curve is one of seven per-period series the run records, and the other six were readable
 * > only as figures at the cursor. The network scope draws all seven as charts: the fill-rate curve
 * > with its resilience triangle, and the rest as stepwise charts of the same kind an element scope
 * > draws."
 *
 * Free of Angular and of the DOM, like `element-charts.ts` beside it - of which this is the network
 * half, returning the same {@link SeriesChart} shape so `series-chart` stays one implementation of
 * "a step line with a fitted axis, a shared cursor, and an absence that is a sentence".
 * `network-charts.spec.ts` pins the readings against
 * `../snrm-backend/samples/four-echelon-playback/README.md` §6.4.
 *
 * ## The series are `network-series.ts`', not this module's
 *
 * Every array here comes from the editor's own derivation of `RUN_TIMESERIES` - the seven quantities
 * FR-19's live block prints on the canvas - so the dashboard's *Period cost* chart and the canvas's
 * *Period cost* sparkline are two drawings of one array. This module chooses which of them are
 * charted, in what order, with what sentence and against which undisrupted twin; it computes no
 * quantity of its own.
 *
 * ## Fill rate is charted by the performance curve, not from here
 *
 * The seventh series is the fill rate, and it keeps the hand-drawn curve rather than
 * becoming a step chart in this list: its y-axis is pinned to [0, 1] where every chart here fits its
 * own range, and the region *between* it and its baseline is the resilience triangle "rendered
 * literally" - a shape no step chart draws. {@link FILL_RATE_CHART} is its key in the page's
 * expanded set, so the curve takes part in the double-click gesture on the same terms as the rest.
 *
 * ## Four of the six have an undisrupted twin, and the schema is why
 *
 * `V6__run_timeseries_baseline.sql` records `baseline_served_demand` and `baseline_cost` and nothing
 * else, so served, period cost and the two running totals derived from them carry the paired overlay
 * - and **on-hand and pipeline are drawn bare**, never overlaid with a copy of themselves, which
 * would claim the disruption moved no stock. That is `network-series.ts`'s own rule, stated here in
 * the place a reader meets it: {@link NO_NETWORK_BASELINE} is printed once beneath the set.
 *
 * ## A running total has no mean worth drawing
 *
 * The two cumulative charts carry `mean: null`, so no reference line is drawn and no figure printed.
 * The mean of a monotone running total is an artefact of where the horizon happens to end rather
 * than a property of the run, and printing one beside `TOTAL_COST` - which *is* the last point of
 * that same line - would offer a reader two numbers for one quantity. The hint says where the total
 * is instead.
 */

/** The key the fill-rate curve is known by in the page's expanded set. */
export const FILL_RATE_CHART = 'fillRate';

/** Printed once under the network scope's charts, for the two that have no overlay. */
export const NO_NETWORK_BASELINE =
  'On-hand and pipeline have no dashed overlay: `RUN_TIMESERIES` records an undisrupted twin for '
  + 'served demand and cost alone. Drawing one as a copy of itself would claim the disruption moved '
  + 'no stock - their horizon means are the AVG_INVENTORY and AVG_PIPELINE cards, which carry proper '
  + 'cross-replication intervals.';

/**
 * The six charts the network scope draws beside its curve, in the order FR-19 reads them.
 *
 * Service first (what the network is for), then money, then what it is holding - the order the
 * editor's network dashboard prints the same rows in. A run with no series answers an empty list
 * rather than six empty charts.
 */
export function networkCharts(series: NetworkSeries): readonly SeriesChart[] {
  if (series.fill.length === 0) {
    return [];
  }
  return [
    {
      key: 'served',
      label: 'Served demand',
      values: series.served,
      baseline: series.baselineServed,
      unit: 'quantity',
      hint:
        'Demand met across the whole network this period, against the undisrupted baseline set every '
        + 'run includes. The gap between the two lines is the shortfall the curve shows as '
        + 'a fill rate.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.served),
      meanLabel: 'mean',
    },
    {
      key: 'cost',
      label: 'Period cost',
      values: series.cost,
      baseline: series.baselineCost,
      unit: 'currency',
      hint: 'Fixed, variable, transport, holding and shortage, for this period alone.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.cost),
      meanLabel: 'mean',
    },
    {
      key: 'cumulativeCost',
      label: 'Cost to date',
      values: series.cumulativeCost,
      baseline: series.cumulativeBaselineCost,
      unit: 'currency',
      hint:
        'Spent through each period, inclusive. Its last point is this run’s TOTAL_COST, and the gap '
        + 'to the dashed line is what the disruption cost - the DISRUPTION_COST_DELTA card.',
      absence: null,
      absentReading: null,
      suppressed: null,
      // See the module note: a running total's mean is an artefact of where the horizon ends.
      mean: null,
      meanLabel: 'mean',
    },
    {
      key: 'cumulativeUnmet',
      label: 'Unmet demand to date',
      values: series.cumulativeUnmet,
      baseline: series.cumulativeBaselineUnmet,
      unit: 'quantity',
      hint:
        'Demand wanted and not got, accumulated through each period. Lost, not backlogged - the '
        + 'engine carries nothing forward, so this line never comes back down.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: null,
      meanLabel: 'mean',
    },
    {
      key: 'onHand',
      label: 'Total on-hand',
      values: series.onHand,
      baseline: null,
      unit: 'quantity',
      hint: 'Stock across the whole network at period end. Its horizon mean is AVG_INVENTORY.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.onHand),
      meanLabel: 'mean',
    },
    {
      key: 'inPipeline',
      label: 'In pipeline',
      values: series.inPipeline,
      baseline: null,
      unit: 'quantity',
      hint: 'Material shipped and not yet arrived. Its horizon mean is AVG_PIPELINE.',
      absence: null,
      absentReading: null,
      suppressed: null,
      mean: seriesMean(series.inPipeline),
      meanLabel: 'mean',
    },
  ];
}
