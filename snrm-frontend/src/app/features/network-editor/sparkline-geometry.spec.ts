import {
  SparkBox,
  cumulativeThrough,
  cursorX,
  seriesMean,
  seriesRange,
  sparkline,
  valueY,
  weightedMean,
} from './sparkline-geometry';

/**
 * The box every path below is worked against, chosen so the arithmetic is exact.
 *
 * ```text
 *   plot width   110 − 2×5 = 100      five periods → slot 20, boundaries at 5, 25, 45, 65, 85, 105
 *   plot height   22 − 2×5 =  12      top 5, bottom 17
 * ```
 */
const BOX: SparkBox = { width: 110, height: 22, pad: 5 };

/**
 * The same module at **chart** size, with the four gutters the results dashboard's element charts
 * need for their axes (FR-22).
 *
 * ```text
 *   plot width   130 − 30 − 10 = 90     three periods → slot 30, boundaries at 30, 60, 90, 120
 *   plot height   60 −  4 − 16 = 40     top 4, bottom 44
 * ```
 *
 * A left gutter of 30 and a bottom of 16 are a value axis and a period axis; a sparkline has neither,
 * which is why {@link BOX} states one pad and every expectation above it is unchanged.
 */
const CHART_BOX: SparkBox = { width: 130, height: 60, pad: 5, left: 30, right: 10, top: 4, bottom: 16 };

/**
 * The four-echelon baseline run, transcribed from
 * `../snrm-backend/samples/four-echelon-playback/README.md` §6.5.
 *
 * Every number there is derived by hand from the per-period loop rather than produced by
 * running the application, so a mean that disagrees with it is a defect in one of the two rather
 * than a rounding difference. These are the same figures `MANUAL-TEST.md` §U reads off the screen.
 */
const SUP_ON_HAND = [10, 10, 20, ...Array<number>(27).fill(10)];
const DC_ON_HAND = [15, 15, ...Array<number>(28).fill(5)];
const CUST_ON_HAND = [10, ...Array<number>(29).fill(0)];
/** Arc **c** `DC-1 → CUST-1`: idle at t=0 because `CUST-1` opened at its target (§6.5.2). */
const LINK_C_FLOW = [0, ...Array<number>(29).fill(10)];
/** `CUST-1`'s inbound lead: null exactly where arc **c** dispatched nothing (§6.5.4). */
const CUST_INBOUND_LEAD = [null, ...Array<number | null>(29).fill(1)];

describe('sparkline-geometry', () => {
  describe('sparkline', () => {
    it('draws a five-point series as bands, with the gap left blank', () => {
      // Values 10, 20, absent, 5, 15 over the range [5, 20]:
      //   y(10) = 5 + (1 − 5/15)×12 = 13     y(20) = 5      y(5) = 17      y(15) = 9
      // Each present period is flat across its own 20-wide band, with a riser where it changes.
      const spark = sparkline([10, 20, null, 5, 15], BOX, [10, 10, 10, 10, 10]);

      expect(spark).toEqual({
        min: 5,
        max: 20,
        count: 5,
        slot: 20,
        left: 5,
        top: 5,
        plotHeight: 12,
        paths: ['M5,13 L25,13 L25,5 L45,5', 'M65,17 L85,17 L85,9 L105,9'],
        baselinePaths: ['M5,13 L25,13 L45,13 L65,13 L85,13 L105,13'],
        empty: false,
      });
    });

    it('breaks the line at an absent period rather than drawing it as zero', () => {
      // A 0 here would say the quantity was zero in that period, and the line would dip to the
      // floor. Two paths and a range that never sees 0 is the whole distinction.
      const spark = sparkline([2, null, 4], BOX);

      expect(spark.paths.length).toBe(2);
      expect(spark.min).toBe(2);
      expect(spark.max).toBe(4);
    });

    it('overlays a baseline identical to the line when the run is a baseline run', () => {
      // `V9__element_timeseries.sql` copies the run's own series into the baseline columns on a run
      // with no scenario (§6.5.5), so the two paths must coincide exactly rather than be absent.
      const spark = sparkline(DC_ON_HAND, BOX, DC_ON_HAND);

      expect(spark.baselinePaths).toEqual(spark.paths);
    });

    it('draws a flat series through the middle rather than at either edge', () => {
      // Its range is degenerate, so there is no fraction to take. The top would read as a maximum
      // and the bottom as an empty node; both are claims a constant series does not make.
      const spark = sparkline([4, 4, 4], BOX);

      expect(spark.min).toBe(4);
      expect(spark.max).toBe(4);
      expect(spark.paths).toEqual(['M5,11 L38.33,11 L71.67,11 L105,11']);
    });

    it('is empty for a series with no periods and for one with no value at all', () => {
      expect(sparkline([], BOX).empty).toBeTrue();
      expect(sparkline([], BOX).paths).toEqual([]);
      expect(sparkline([null, null], BOX).empty).toBeTrue();
      expect(sparkline([null, null], BOX).paths).toEqual([]);
    });

    it('takes the range over both series, so the overlay is never clipped', () => {
      expect(seriesRange([1, 2], [0, 9])).toEqual({ min: 0, max: 9 });
      expect(seriesRange([null, null])).toBeNull();
    });

    // ------------------------------------------------------- the chart-sized box (FR-22)

    it('draws the same step line against four gutters instead of one pad', () => {
      // The results dashboard draws these lines at chart size with a value axis down the left and a
      // period axis along the bottom, which are not the same width. Everything else about the line
      // is unchanged - a period is a band, a riser marks a change - which is the whole reason the
      // dashboard reuses this module rather than growing a fourth geometry.
      const spark = sparkline([0, 10, 5], CHART_BOX);

      expect(spark.left).toBe(30);
      expect(spark.top).toBe(4);
      expect(spark.slot).toBe(30);
      expect(spark.plotHeight).toBe(40);
      expect(spark.paths).toEqual(['M30,44 L60,44 L60,4 L90,4 L90,24 L120,24']);
    });

    it('is byte-identical to the one-pad form when the four gutters state that pad', () => {
      // Which is what keeps every expectation above this line pinning the same numbers it did before
      // the gutters existed: an omitted side *is* `pad`.
      const values = [10, 20, null, 5, 15];

      expect(sparkline(values, BOX)).toEqual(
        sparkline(values, { width: 110, height: 22, pad: 5, left: 5, right: 5, top: 5, bottom: 5 }),
      );
    });
  });

  describe('cursorX', () => {
    it('stands at the centre of the period band, because a period is an interval', () => {
      const spark = sparkline([10, 20, null, 5, 15], BOX);

      expect(cursorX(0, spark)).toBe(15);
      expect(cursorX(2, spark)).toBe(55);
      expect(cursorX(4, spark)).toBe(95);
    });

    it('clamps into the series rather than leaving the box', () => {
      const spark = sparkline([10, 20, null, 5, 15], BOX);

      expect(cursorX(-3, spark)).toBe(15);
      expect(cursorX(9, spark)).toBe(95);
      expect(cursorX(0, sparkline([], BOX))).toBe(5);
    });
  });

  describe('valueY', () => {
    it('places a reference line on the same scale as the path it crosses', () => {
      const spark = sparkline([10, 20, null, 5, 15], BOX);
      // The mean of the four present values is 12.5, exactly half way up the [5, 20] range.
      expect(seriesMean([10, 20, null, 5, 15])).toBe(12.5);
      expect(valueY(12.5, spark)).toBe(11);
      expect(valueY(20, spark)).toBe(5);
      expect(valueY(5, spark)).toBe(17);
    });

    it('clamps a value from outside the range into the box', () => {
      const spark = sparkline([10, 20, null, 5, 15], BOX);

      expect(valueY(1000, spark)).toBe(5);
      expect(valueY(-1000, spark)).toBe(17);
    });
  });

  describe('seriesMean', () => {
    it('reproduces the four-echelon averages the inspector prints', () => {
      // §6.5.1, over the whole 30-period horizon. The 2 dp figures are what MANUAL-TEST.md §U reads.
      expect(seriesMean(DC_ON_HAND)).toBeCloseTo(170 / 30, 10);
      expect(Number(seriesMean(DC_ON_HAND)!.toFixed(2))).toBe(5.67);
      expect(Number(seriesMean(SUP_ON_HAND)!.toFixed(2))).toBe(10.33);
      expect(Number(seriesMean(CUST_ON_HAND)!.toFixed(2))).toBe(0.33);
    });

    it('skips absent periods rather than averaging them in as zero', () => {
      expect(seriesMean([4, null, 8])).toBe(6);
      expect(seriesMean([null, null])).toBeNull();
      expect(seriesMean([])).toBeNull();
    });
  });

  describe('weightedMean', () => {
    it('weights each period by what it dispatched', () => {
      // §6.5.4: `CUST-1`'s lead is null at t=0, where arc c carried nothing, and 1.0 in the other
      // twenty-nine periods, where it carried 10 - a flow-weighted mean of exactly one period.
      expect(weightedMean(CUST_INBOUND_LEAD, LINK_C_FLOW)).toBe(1);
    });

    it('is null where nothing was ever dispatched, rather than zero', () => {
      // `SUP-1` is the structural case: a supply origin has no inbound arc at all, so its inbound
      // lead is undefined in every period of every run this network can produce (§6.5.4).
      const never = Array<number>(30).fill(0);
      expect(weightedMean(Array<number | null>(30).fill(null), never)).toBeNull();
      expect(weightedMean([1, 1, 1], never.slice(0, 3))).toBeNull();
    });

    it('lets a heavy period dominate a light one', () => {
      // 1 period on 90 units and 5 periods on 10: the plain mean is 3, the weighted mean 1.4.
      expect(weightedMean([1, 5], [90, 10])).toBeCloseTo(1.4, 10);
    });
  });

  describe('cumulativeThrough', () => {
    it('counts the period on screen, inclusive', () => {
      // §8.2: the disrupted run loses ten units of demand in each of periods 11, 12 and 13.
      const unserved = Array<number>(30).fill(0);
      unserved[11] = 10;
      unserved[12] = 10;
      unserved[13] = 10;

      expect(cumulativeThrough(unserved, 10)).toBe(0);
      expect(cumulativeThrough(unserved, 11)).toBe(10);
      expect(cumulativeThrough(unserved, 13)).toBe(30);
      expect(cumulativeThrough(unserved, 29)).toBe(30);
    });

    it('clamps past the end and skips absent periods', () => {
      expect(cumulativeThrough([1, null, 2], 99)).toBe(3);
      expect(cumulativeThrough([], 3)).toBe(0);
    });
  });
});
