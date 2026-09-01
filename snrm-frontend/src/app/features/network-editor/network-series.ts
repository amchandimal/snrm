import { RunTimeseriesPoint } from '../../core/models';

/**
 * The network dashboard's live series, as arithmetic (FR-19).
 *
 * Free of Angular and of the DOM, like `sparkline-geometry.ts` beside it and
 * `features/simulations/curve-geometry.ts` before it. Same reason, one size down again: the mapping
 * from `RUN_TIMESERIES` to the seven rows the dashboard prints is the part that has to be right, and
 * a pure function is the only version of it that can be pinned against hand-worked numbers.
 * `network-series.spec.ts` pins every array below against
 * `samples/four-echelon-playback/README.md` §6.4.
 *
 * ## Why this exists rather than seven inline expressions
 *
 * Each row is drawn *and* printed - the number at the playback clock's period, and a sparkline over
 * the whole horizon - so every quantity is needed as a series anyway. Deriving the printed figure
 * separately from the drawn one is exactly how a card comes to disagree with the line beside it,
 * which is the mistake `ElementInspectorComponent.average` already documents for its own mean.
 *
 * ## Two of the seven have no baseline, and that is the schema saying so
 *
 * `RUN_TIMESERIES` carries `baseline_served_demand` and `baseline_cost` and nothing else
 * (`V6__run_timeseries_baseline.sql`), so fill rate, served, cost and unmet all have an undisrupted
 * twin to overlay and **on-hand and pipeline do not**. The dashboard draws no overlay on those two
 * rather than overlaying them with a copy of themselves, which would claim the disruption moved no
 * stock. Their horizon means are `AVG_INVENTORY` and `AVG_PIPELINE`, which the run suite block
 * carries with proper cross-replication intervals - the honest place for that comparison.
 *
 * ## Cumulative rows are running totals, not the metric
 *
 * `cumulativeCost[t]` is what the run had spent by the end of period *t*, so its last entry is
 * `TOTAL_COST` for the disrupted set. That coincidence is worth knowing and worth not relying on:
 * the metric averages per-replication totals and this averages per-period means, which agree exactly
 * because both are linear - unlike `LOSS_AREA`, where the `max(0, ·)` makes the drawn figure a lower
 * bound. Nothing here is a substitute for the persisted suite.
 */

/** Every per-period series the dashboard's live block reads, indexed by period. */
export interface NetworkSeries {
  /** Served over demanded, per period; 1 where nothing was demanded. */
  readonly fill: readonly number[];
  /** The same for the undisrupted baseline set, over the **shared** demand. */
  readonly baselineFill: readonly number[];
  readonly served: readonly number[];
  readonly demand: readonly number[];
  readonly baselineServed: readonly number[];
  readonly cost: readonly number[];
  readonly baselineCost: readonly number[];
  readonly cumulativeCost: readonly number[];
  readonly cumulativeBaselineCost: readonly number[];
  /** Demand wanted and not got, per period. */
  readonly unmet: readonly number[];
  readonly cumulativeUnmet: readonly number[];
  readonly cumulativeBaselineUnmet: readonly number[];
  /** Whole-network end-of-period stock. No baseline column exists - see the module note. */
  readonly onHand: readonly number[];
  /** Whole-network in-transit quantity. Likewise no baseline. */
  readonly inPipeline: readonly number[];
}

/** An empty set, so a caller with no report needs no null branch per row. */
const EMPTY: NetworkSeries = {
  fill: [],
  baselineFill: [],
  served: [],
  demand: [],
  baselineServed: [],
  cost: [],
  baselineCost: [],
  cumulativeCost: [],
  cumulativeBaselineCost: [],
  unmet: [],
  cumulativeUnmet: [],
  cumulativeBaselineUnmet: [],
  onHand: [],
  inPipeline: [],
};

/**
 * One run's per-period curve, split into the series the dashboard draws.
 *
 * Points are taken in the order given, which is the order the API returns them - `period` is carried
 * on each row but is not used as an index here, because a gap in the series would then silently
 * become a zero, and there is no such thing as a run missing a period (`RUN_TIMESERIES`'s primary
 * key is `(run_id, period)` and every period is written).
 */
export function networkSeries(points: readonly RunTimeseriesPoint[]): NetworkSeries {
  if (points.length === 0) {
    return EMPTY;
  }
  const fill: number[] = [];
  const baselineFill: number[] = [];
  const served: number[] = [];
  const demand: number[] = [];
  const baselineServed: number[] = [];
  const cost: number[] = [];
  const baselineCost: number[] = [];
  const unmet: number[] = [];
  const baselineUnmet: number[] = [];
  const onHand: number[] = [];
  const inPipeline: number[] = [];

  for (const point of points) {
    // The engine's own reading: a period nobody demanded anything of was fully served
    // (`PeriodTrace.fillRate`). Zero would punish a network for a quiet period.
    fill.push(point.totalDemand <= 0 ? 1 : point.servedDemand / point.totalDemand);
    baselineFill.push(point.totalDemand <= 0 ? 1 : point.baselineServedDemand / point.totalDemand);
    served.push(point.servedDemand);
    demand.push(point.totalDemand);
    baselineServed.push(point.baselineServedDemand);
    cost.push(point.cost);
    baselineCost.push(point.baselineCost);
    // Floored at zero: the two figures are replication means and can cross by a rounding hair on a
    // fully served period, which would otherwise print a negative shortfall.
    unmet.push(Math.max(0, point.totalDemand - point.servedDemand));
    baselineUnmet.push(Math.max(0, point.totalDemand - point.baselineServedDemand));
    onHand.push(point.endingInventory);
    inPipeline.push(point.inPipeline);
  }

  return {
    fill,
    baselineFill,
    served,
    demand,
    baselineServed,
    cost,
    baselineCost,
    cumulativeCost: runningTotal(cost),
    cumulativeBaselineCost: runningTotal(baselineCost),
    unmet,
    cumulativeUnmet: runningTotal(unmet),
    cumulativeBaselineUnmet: runningTotal(baselineUnmet),
    onHand,
    inPipeline,
  };
}

/**
 * A series turned into its running totals, inclusive of each period.
 *
 * `runningTotal([1, 2, 3])` is `[1, 3, 6]` - entry *t* is the total **through** period *t*, so the
 * figure printed beside period *t*'s own value already includes it. That is the same convention
 * `sparkline-geometry.cumulativeThrough` takes, and for the same reason: a cumulative unmet count
 * shown next to a stockout of 10 that excluded those 10 would read as the loss before the one on
 * screen.
 *
 * Non-finite entries contribute nothing rather than poisoning every later total - one absent point
 * must not turn the rest of the line into `NaN`.
 */
export function runningTotal(values: readonly number[]): number[] {
  const totals: number[] = [];
  let total = 0;
  for (const value of values) {
    if (Number.isFinite(value)) {
      total += value;
    }
    totals.push(total);
  }
  return totals;
}

/**
 * One period of a series, or null where it has none.
 *
 * **Null rather than a clamp to the nearest present value.** The dashboard prints these into
 * sentences, where a substituted number is a claim; `playback-channels.valueAt` clamps because it
 * feeds a Cytoscape style, where a number is required. The index is floored and range-checked, so a
 * clock momentarily ahead of a shorter series reads absent instead of throwing.
 */
export function valueAtPeriod(values: readonly number[], period: number): number | null {
  if (!values.length) {
    return null;
  }
  const index = Math.floor(period);
  if (!Number.isFinite(index) || index < 0 || index >= values.length) {
    return null;
  }
  const value = values[index];
  return Number.isFinite(value) ? value : null;
}
