import {
  CurvePoint,
  lossRegions,
  onsetPeriod,
  periodTicks,
  regionPath,
  scaleFor,
  shadedArea,
  toCurve,
} from './curve-geometry';
import { RunTimeseriesPoint } from '../../core/models';

/** A timeseries row, with the served/baseline pair the test cares about. */
function point(
  period: number,
  totalDemand: number,
  servedDemand: number,
  baselineServedDemand: number,
): RunTimeseriesPoint {
  return {
    period,
    totalDemand,
    servedDemand,
    baselineServedDemand,
    cost: 0,
    baselineCost: 0,
    endingInventory: 0,
    inPipeline: 0,
  };
}

/** A curve point directly, for the geometry tests. */
function curve(period: number, fill: number, baselineFill: number): CurvePoint {
  return { period, fill, baselineFill };
}

describe('curve-geometry', () => {
  describe('toCurve', () => {
    it('normalises both series against the shared demand', () => {
      expect(toCurve([point(0, 50, 40, 50)])).toEqual([
        { period: 0, fill: 0.8, baselineFill: 1 },
      ]);
    });

    it('treats a period with no demand as fully served, not as 0/0', () => {
      // The rule RunTimeseriesDto.fillRate() and the FILL_RATE calculator both apply. Without it the
      // curve would have a hole wherever demand happened to be zero.
      expect(toCurve([point(3, 0, 0, 0)])).toEqual([{ period: 3, fill: 1, baselineFill: 1 }]);
    });
  });

  describe('lossRegions', () => {
    it('finds nothing when the network absorbed the scenario', () => {
      const regions = lossRegions([curve(0, 1, 1), curve(1, 1, 1), curve(2, 1, 1)]);
      expect(regions).toEqual([]);
      expect(onsetPeriod(regions)).toBeNull();
    });

    it('opens and closes the region at the crossings, not at the samples', () => {
      // Baseline flat at 1. Disrupted: 1 → 0.5 → 1. The gap is 0 at period 0 and period 2, so the
      // region is exactly one period wide either side of the trough.
      const regions = lossRegions([curve(0, 1, 1), curve(1, 0.5, 1), curve(2, 1, 1)]);
      expect(regions.length).toBe(1);
      expect(regions[0][0]).toEqual({ period: 0, upper: 1, lower: 1 });
      expect(regions[0][regions[0].length - 1]).toEqual({ period: 2, upper: 1, lower: 1 });
    });

    it('interpolates a crossing that falls between two periods', () => {
      // Gap goes +0.2 → −0.2 across one period, so the curves cross at the midpoint.
      const regions = lossRegions([curve(0, 0.8, 1), curve(1, 1.2, 1)]);
      expect(regions.length).toBe(1);
      const closing = regions[0][regions[0].length - 1];
      expect(closing.period).toBeCloseTo(0.5, 10);
      expect(closing.upper).toBeCloseTo(closing.lower, 10);
    });

    it('shades only shortfalls, never the stretches where disrupted beats baseline', () => {
      // Demand noise can put a disrupted replication above its baseline. LOSS_AREA's max(0, …)
      // refuses to net that against a real loss, and so must the drawing.
      const regions = lossRegions([
        curve(0, 0.5, 1),
        curve(1, 0.5, 1),
        curve(2, 1.0, 1),
        curve(3, 1.2, 1),
      ]);
      expect(regions.length).toBe(1);
      expect(regions[0].every((vertex) => vertex.upper >= vertex.lower)).toBeTrue();
    });

    it('splits a recovery-then-relapse into two regions', () => {
      const regions = lossRegions([
        curve(0, 1, 1),
        curve(1, 0.4, 1),
        curve(2, 1, 1),
        curve(3, 0.6, 1),
        curve(4, 1, 1),
      ]);
      expect(regions.length).toBe(2);
      expect(onsetPeriod(regions)).toBe(0);
    });

    it('leaves a region open-ended when the horizon ends mid-shortfall', () => {
      // A run censored by its horizon - the same case TTR reports as a lower bound.
      const regions = lossRegions([curve(0, 1, 1), curve(1, 0.5, 1), curve(2, 0.5, 1)]);
      expect(regions.length).toBe(1);
      expect(regions[0][regions[0].length - 1].period).toBe(2);
    });
  });

  describe('shadedArea', () => {
    it('measures a half-height shortfall over two periods as 1.0', () => {
      // Performance halved from period 1 to 3, baseline flat at 1. Trapezoids: 0→1 rises 0 to 0.5
      // (0.25), 1→2 flat at 0.5 (0.5), 2→3 falls to 0 (0.25). Total 1.0 fill·periods.
      const regions = lossRegions([
        curve(0, 1, 1),
        curve(1, 0.5, 1),
        curve(2, 0.5, 1),
        curve(3, 1, 1),
      ]);
      expect(shadedArea(regions)).toBeCloseTo(1, 10);
    });

    it('is zero for a network that never fell short', () => {
      expect(shadedArea(lossRegions([curve(0, 1, 1), curve(1, 1, 1)]))).toBe(0);
    });
  });

  describe('scaleFor', () => {
    const box = { width: 100, height: 100, left: 0, top: 0, right: 0, bottom: 0 };

    it('pins the y-axis to [0,1] rather than fitting the data', () => {
      // Auto-fitting would draw the same disruption as a cliff on one variant and a dip on another
      // purely because their minima differ.
      const scale = scaleFor(box, 11);
      expect(scale.y(1)).toBe(0);
      expect(scale.y(0)).toBe(100);
      expect(scale.y(0.5)).toBe(50);
    });

    it('spreads the horizon across the full width', () => {
      const scale = scaleFor(box, 11);
      expect(scale.x(0)).toBe(0);
      expect(scale.x(10)).toBe(100);
    });

    it('survives a single-period horizon without dividing by zero', () => {
      const scale = scaleFor(box, 1);
      expect(Number.isFinite(scale.x(0))).toBeTrue();
    });
  });

  describe('regionPath', () => {
    it('closes the polygon', () => {
      const scale = scaleFor({ width: 10, height: 10, left: 0, top: 0, right: 0, bottom: 0 }, 3);
      const path = regionPath(lossRegions([curve(0, 1, 1), curve(1, 0.5, 1), curve(2, 1, 1)])[0], scale);
      expect(path.startsWith('M')).toBeTrue();
      expect(path.endsWith('Z')).toBeTrue();
    });
  });

  describe('periodTicks', () => {
    it('always includes the first and last period', () => {
      const ticks = periodTicks(52);
      expect(ticks[0]).toBe(0);
      expect(ticks[ticks.length - 1]).toBe(51);
    });

    it('thins a long horizon rather than drawing a tick per period', () => {
      expect(periodTicks(365).length).toBeLessThan(12);
    });

    it('handles a one-period horizon', () => {
      expect(periodTicks(1)).toEqual([0]);
    });
  });
});
