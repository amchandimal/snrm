import { RunTimeseriesPoint } from '../../core/models';
import { networkSeries } from '../network-editor/network-series';
import { FILL_RATE_CHART, networkCharts } from './network-charts';

/**
 * What the network scope charts beside its curve.
 *
 * The figures are §6.4 of `../snrm-backend/samples/four-echelon-playback/README.md`,
 * derived there by hand - the same source `network-series.spec.ts` reads, because these charts draw
 * that module's arrays and add no quantity of their own. What is pinned here is which series are
 * charted, which of them the schema gives an undisrupted twin, and which of them may not print a
 * mean.
 */

function point(
  period: number,
  totalDemand: number,
  servedDemand: number,
  cost: number,
  endingInventory: number,
  inPipeline: number,
  baselineServedDemand = servedDemand,
  baselineCost = cost,
): RunTimeseriesPoint {
  return {
    period,
    totalDemand,
    servedDemand,
    cost,
    baselineServedDemand,
    baselineCost,
    endingInventory,
    inPipeline,
  };
}

/** §6.4, periods 0–5 of the baseline run. */
const BASELINE = networkSeries([
  point(0, 10, 10, 404, 40, 20),
  point(1, 10, 10, 405, 40, 20),
  point(2, 10, 10, 405, 40, 20),
  point(3, 10, 10, 402, 20, 30),
  point(4, 10, 10, 403, 20, 30),
  point(5, 10, 10, 403, 20, 30),
]);

/** §8.2, the stockout: served falls to 0 while the baseline set keeps serving 10. */
const DISRUPTED = networkSeries([
  point(10, 10, 10, 404, 20, 20, 10, 403),
  point(11, 10, 0, 606, 50, 10, 10, 403),
  point(12, 10, 0, 608, 70, 0, 10, 403),
  point(13, 10, 0, 605, 50, 20, 10, 403),
]);

describe('network-charts', () => {
  it('draws the six series the curve does not, in the order FR-19 reads them', () => {
    expect(networkCharts(BASELINE).map((chart) => chart.key)).toEqual([
      'served',
      'cost',
      'cumulativeCost',
      'cumulativeUnmet',
      'onHand',
      'inPipeline',
    ]);
  });

  it('leaves the fill rate to the performance curve', () => {
    // Its y-axis is pinned to [0,1] and the region between it and its baseline is the resilience
    // triangle "rendered literally" - a shape no step chart draws. It keeps its own
    // component and takes part in the expand gesture through this key alone.
    expect(networkCharts(BASELINE).some((chart) => chart.key === FILL_RATE_CHART)).toBeFalse();
  });

  it('draws the editor’s own arrays rather than deriving anything', () => {
    const charts = networkCharts(BASELINE);

    expect(charts[0].values).toBe(BASELINE.served);
    expect(charts[1].values).toBe(BASELINE.cost);
    expect(charts[2].values).toBe(BASELINE.cumulativeCost);
    expect(charts[4].values).toBe(BASELINE.onHand);
    expect(charts[5].values).toBe(BASELINE.inPipeline);
  });

  it('overlays a baseline only where `RUN_TIMESERIES` records one', () => {
    // `V6__run_timeseries_baseline.sql` carries `baseline_served_demand` and `baseline_cost` alone,
    // so on-hand and pipeline are drawn bare. Overlaying them with a copy of themselves would claim
    // the disruption moved no stock - `network-series.ts`'s rule, one surface up.
    const paired = networkCharts(BASELINE)
      .filter((chart) => chart.baseline !== null)
      .map((chart) => chart.key);

    expect(paired).toEqual(['served', 'cost', 'cumulativeCost', 'cumulativeUnmet']);
  });

  it('prints no mean for a running total', () => {
    // The mean of a monotone total is an artefact of where the horizon ends, and `TOTAL_COST` - the
    // last point of that same line - is already on a card. One quantity, one number.
    const charts = networkCharts(BASELINE);

    expect(charts[2].mean).toBeNull();
    expect(charts[3].mean).toBeNull();
    // The others keep theirs, drawn as the reference line the caption describes.
    expect(charts[1].mean).toBeCloseTo(403.67, 2);
    // 40, 40, 40, 20, 20, 20 - the warm-up draining into the steady state.
    expect(charts[4].mean).toBe(30);
  });

  it('writes money as money and quantities plainly', () => {
    const charts = networkCharts(BASELINE);

    expect(charts.filter((chart) => chart.unit === 'currency').map((chart) => chart.key)).toEqual([
      'cost',
      'cumulativeCost',
    ]);
    expect(charts[0].unit).toBe('quantity');
  });

  it('carries no absences at all - every network series is written every period', () => {
    // `RUN_TIMESERIES`'s primary key is `(run_id, period)` and every period is written, so unlike the
    // element series there is no nullable column here and nothing to explain a gap with.
    const charts = networkCharts(DISRUPTED);

    expect(charts.every((chart) => chart.absence === null)).toBeTrue();
    expect(charts.every((chart) => chart.absentReading === null)).toBeTrue();
    expect(charts.every((chart) => chart.suppressed === null)).toBeTrue();
  });

  it('shows the stockout as the gap between the two lines it charts (§8.2)', () => {
    const charts = networkCharts(DISRUPTED);
    const served = charts[0];
    const unmet = charts[3];

    expect(served.values).toEqual([10, 0, 0, 0]);
    expect(served.baseline).toEqual([10, 10, 10, 10]);
    // Lost, not backlogged: the cumulative line never comes back down.
    expect(unmet.values).toEqual([0, 10, 20, 30]);
    expect(unmet.baseline).toEqual([0, 0, 0, 0]);
  });

  it('answers an empty list for a run with no series rather than six empty charts', () => {
    expect(networkCharts(networkSeries([]))).toEqual([]);
  });
});
