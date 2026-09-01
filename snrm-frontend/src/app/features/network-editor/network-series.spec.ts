import { RunTimeseriesPoint } from '../../core/models';
import { networkSeries, runningTotal, valueAtPeriod } from './network-series';

/**
 * `network-series.ts` against hand-worked numbers.
 *
 * The horizon used below is the **first six periods** of
 * `samples/four-echelon-playback/README.md` §5.1 and §6.4 - the warm-up and the start of the steady
 * state, which is where every column changes at least once. The disruption run of that document's
 * §8.2 supplies the stockout case, which is the only place the served and baseline curves differ.
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

/** §6.4, periods 0–5 of the baseline run: 404, 405, 405, 402, 403, 403. */
const BASELINE: readonly RunTimeseriesPoint[] = [
  point(0, 10, 10, 404, 40, 20),
  point(1, 10, 10, 405, 40, 20),
  point(2, 10, 10, 405, 40, 20),
  point(3, 10, 10, 402, 20, 30),
  point(4, 10, 10, 403, 20, 30),
  point(5, 10, 10, 403, 20, 30),
];

describe('network-series', () => {
  describe('runningTotal', () => {
    it('is inclusive of each period', () => {
      expect(runningTotal([1, 2, 3])).toEqual([1, 3, 6]);
    });

    it('carries an empty series through as empty', () => {
      expect(runningTotal([])).toEqual([]);
    });

    it('skips a non-finite entry rather than poisoning every later total', () => {
      expect(runningTotal([1, Number.NaN, 2])).toEqual([1, 1, 3]);
    });
  });

  describe('networkSeries', () => {
    it('answers empty arrays for a run with no points, not nulls', () => {
      const series = networkSeries([]);
      expect(series.fill).toEqual([]);
      expect(series.cumulativeCost).toEqual([]);
      expect(series.onHand).toEqual([]);
    });

    it('reads the two Stage-2 columns straight off the row (§6.4)', () => {
      const series = networkSeries(BASELINE);
      expect(series.onHand).toEqual([40, 40, 40, 20, 20, 20]);
      expect(series.inPipeline).toEqual([20, 20, 20, 30, 30, 30]);
    });

    it('has no baseline twin for those two - the schema carries none', () => {
      // Asserted by absence deliberately: a `baselineOnHand` here would have to be a copy of
      // `onHand`, which would draw an overlay claiming the disruption moved no stock.
      const series = networkSeries(BASELINE);
      expect(Object.keys(series)).not.toContain('baselineOnHand');
      expect(Object.keys(series)).not.toContain('baselineInPipeline');
    });

    it('accumulates cost to the run total (§5.4 cross-check)', () => {
      const series = networkSeries(BASELINE);
      expect(series.cost).toEqual([404, 405, 405, 402, 403, 403]);
      expect(series.cumulativeCost).toEqual([404, 809, 1214, 1616, 2019, 2422]);
      // 404 + 405 + 405 + 402 = 1616 is the document's own subtotal for the warm-up.
      expect(series.cumulativeCost[3]).toBe(1616);
    });

    it('normalises both fill curves against the shared demand', () => {
      const series = networkSeries(BASELINE);
      expect(series.fill).toEqual([1, 1, 1, 1, 1, 1]);
      expect(series.baselineFill).toEqual([1, 1, 1, 1, 1, 1]);
    });

    it('reads a period with no demand as fully served rather than as 0/0', () => {
      const series = networkSeries([point(0, 0, 0, 400, 20, 30)]);
      expect(series.fill).toEqual([1]);
      expect(series.baselineFill).toEqual([1]);
      expect(series.unmet).toEqual([0]);
    });

    /** §8.2, periods 10–13: served 10, 0, 0, 0 against a baseline that keeps serving 10. */
    it('counts unmet demand cumulatively, and never negatively', () => {
      const disrupted = [
        point(10, 10, 10, 404, 20, 20, 10, 403),
        point(11, 10, 0, 606, 50, 10, 10, 403),
        point(12, 10, 0, 608, 70, 0, 10, 403),
        point(13, 10, 0, 605, 50, 20, 10, 403),
      ];
      const series = networkSeries(disrupted);

      expect(series.unmet).toEqual([0, 10, 10, 10]);
      expect(series.cumulativeUnmet).toEqual([0, 10, 20, 30]);
      // The baseline served everything, so its shortfall is flat zero - the row a stockout is read
      // against.
      expect(series.cumulativeBaselineUnmet).toEqual([0, 0, 0, 0]);
      expect(series.fill).toEqual([1, 0, 0, 0]);
      expect(series.baselineFill).toEqual([1, 1, 1, 1]);
    });

    it('floors a rounding-hair overshoot at zero rather than printing a negative shortfall', () => {
      const series = networkSeries([point(0, 10, 10.000000000000002, 400, 20, 30)]);
      expect(series.unmet).toEqual([0]);
    });

    it('keeps the two cost curves apart', () => {
      const series = networkSeries([point(0, 10, 0, 606, 50, 10, 10, 403)]);
      expect(series.cost).toEqual([606]);
      expect(series.baselineCost).toEqual([403]);
      expect(series.cumulativeCost).toEqual([606]);
      expect(series.cumulativeBaselineCost).toEqual([403]);
    });
  });

  describe('valueAtPeriod', () => {
    it('reads the period asked for', () => {
      expect(valueAtPeriod([40, 40, 40, 20], 3)).toBe(20);
    });

    it('floors a fractional period onto the band it sits in', () => {
      expect(valueAtPeriod([40, 41, 42], 1.9)).toBe(41);
    });

    it('answers null past the end rather than clamping to the last value', () => {
      // A clamp would print period 29's stock as period 40's, which is a claim about a period the
      // run does not have.
      expect(valueAtPeriod([40, 41], 2)).toBeNull();
      expect(valueAtPeriod([40, 41], -1)).toBeNull();
      expect(valueAtPeriod([], 0)).toBeNull();
    });

    it('answers null for a non-finite entry', () => {
      expect(valueAtPeriod([Number.NaN], 0)).toBeNull();
    });
  });
});
