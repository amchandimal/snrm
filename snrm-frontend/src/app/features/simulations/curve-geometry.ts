import { RunTimeseriesPoint } from '../../core/models';

/**
 * The performance curve and the resilience triangle, as arithmetic.
 *
 * > "fill-rate-vs-period curve with baseline overlay and shaded loss area (the resilience triangle
 * > rendered literally)"
 *
 * Free of Angular and of the DOM, like `core/time-units.ts` and
 * `features/scenario-builder/timeline.ts`. The reason is the same: turning two curves into a closed
 * polygon that shades exactly the region between them is the part that has to be right, and a pure
 * function is the only version of it that can be tested against hand-worked numbers.
 *
 * ## What the shaded area is, and what it is not
 *
 * The polygon covers `max(0, baselineFill − fill)` across the horizon, which is `LOSS_AREA`'s
 * definition applied to the curves on screen. It is **not** guaranteed to equal the
 * `LOSS_AREA` the backend reports, and the gap is structural rather than a rounding difference:
 *
 * ```text
 *   LOSS_AREA  = mean_r  Σ_t max(0, b_r(t) − d_r(t))     - shortfall per replication, then averaged
 *   shaded     =         Σ_t max(0, mean_r b_r(t) − mean_r d_r(t))
 * ```
 *
 * `RUN_TIMESERIES` stores replication averages, so the curve is the second form: the mean
 * and the `max(0, …)` in the other order. Because `max(0, …)` is convex, the shaded area is a lower
 * bound on the metric, and the two coincide exactly when the run is deterministic - which every
 * hand-checkable example is. The dashboard therefore reports `LOSS_AREA` as a number from the metric
 * card and lets the shading show its *shape*, rather than claiming the drawing measures it.
 *
 * ## Only shortfalls are shaded
 *
 * Where the disrupted curve runs above its baseline - which demand noise makes possible - nothing is
 * shaded. Filling those stretches in a second colour would invite the reader to net them against the
 * loss, and `LOSS_AREA`'s `max(0, …)` exists precisely to refuse that: the area under the baseline is
 * what "loss" means.
 *
 * Region boundaries are **interpolated to the crossing point** rather than snapped to the nearest
 * period, so the polygon closes where the curves actually meet. Snapping would over- or under-shade
 * by up to half a period at each end of every region, which on a short disruption is most of it.
 */

/** One period of the curve, normalised: both series as fill rates in [0,1]. */
export interface CurvePoint {
  readonly period: number;
  /** Served / total demand in the disrupted run. */
  readonly fill: number;
  /** The same period of the undisrupted baseline set. */
  readonly baselineFill: number;
}

/** A vertex of a shaded region: the two curves at one x, which coincide at a crossing. */
export interface LossVertex {
  readonly period: number;
  /** The upper bound of the shaded band - the baseline curve. */
  readonly upper: number;
  /** The lower bound - the disrupted curve. */
  readonly lower: number;
}

/** One contiguous stretch where the disrupted curve runs below its baseline. */
export type LossRegion = readonly LossVertex[];

/**
 * A run's timeseries as fill rates.
 *
 * A period with no demand is fully served by definition - the same rule `RunTimeseriesDto.fillRate()`
 * and the `FILL_RATE` calculator apply, and the alternative is `0/0`, which would draw a hole in the
 * curve wherever demand happened to be zero.
 */
export function toCurve(points: readonly RunTimeseriesPoint[]): CurvePoint[] {
  return points.map((point) => ({
    period: point.period,
    fill: point.totalDemand <= 0 ? 1 : point.servedDemand / point.totalDemand,
    baselineFill: point.totalDemand <= 0 ? 1 : point.baselineServedDemand / point.totalDemand,
  }));
}

/**
 * The stretches to shade, with their ends interpolated to where the curves cross.
 *
 * Returns one region per contiguous shortfall. A run that was never disrupted returns none, which is
 * the honest drawing of a network that absorbed everything - not an empty polygon at zero height.
 */
export function lossRegions(points: readonly CurvePoint[]): LossRegion[] {
  const regions: LossVertex[][] = [];
  let current: LossVertex[] | null = null;

  for (let i = 0; i < points.length; i++) {
    const point = points[i];
    const gap = point.baselineFill - point.fill;

    if (gap > 0) {
      if (!current) {
        current = [];
        if (i > 0) {
          // Entering a shortfall: open the region at the crossing rather than at this period, or the
          // shading starts up to a whole period late.
          current.push(crossing(points[i - 1], point));
        }
      }
      current.push({ period: point.period, upper: point.baselineFill, lower: point.fill });
      continue;
    }

    if (current) {
      // Leaving it: close at the crossing, for the same reason.
      current.push(crossing(points[i - 1], point));
      regions.push(current);
      current = null;
    }
  }

  if (current) {
    regions.push(current);
  }
  // A region of one vertex has no width and would render as nothing; dropping it keeps the caller
  // from having to reason about degenerate polygons.
  return regions.filter((region) => region.length >= 2);
}

/**
 * Where two consecutive samples' gap changes sign - a vertex on both curves at once.
 *
 * Linear interpolation, matching the straight segments the curve is drawn with: the crossing has to
 * be where the *rendered* lines meet, or the polygon's edge and the curve it is bounded by will not
 * coincide on screen.
 */
function crossing(before: CurvePoint, after: CurvePoint): LossVertex {
  const gapBefore = before.baselineFill - before.fill;
  const gapAfter = after.baselineFill - after.fill;
  const denominator = gapBefore - gapAfter;
  // Both gaps equal means the curves are parallel across the segment; there is no crossing to find,
  // so the boundary is taken at the sample. Guarding it keeps a 0/0 out of a coordinate.
  const t = denominator === 0 ? 0 : gapBefore / denominator;
  const clamped = Math.max(0, Math.min(1, t));
  const period = before.period + clamped * (after.period - before.period);
  const value = before.fill + clamped * (after.fill - before.fill);
  return { period, upper: value, lower: value };
}

/**
 * The area the shading covers, in fill-rate × periods.
 *
 * Trapezoidal over the interpolated regions, so it is the area of the polygon actually drawn. Read
 * the class note before comparing it with the `LOSS_AREA` metric card: they answer the same question
 * about different objects, and only coincide on a deterministic run.
 */
export function shadedArea(regions: readonly LossRegion[]): number {
  let total = 0;
  for (const region of regions) {
    for (let i = 1; i < region.length; i++) {
      const left = region[i - 1];
      const right = region[i];
      const width = right.period - left.period;
      const heightLeft = left.upper - left.lower;
      const heightRight = right.upper - right.lower;
      total += ((heightLeft + heightRight) / 2) * width;
    }
  }
  return total;
}

/**
 * The first period in which the disrupted curve falls below its baseline - where the triangle opens.
 *
 * Used to mark the onset on the chart. Null when no period falls short, which is a network that
 * absorbed the scenario entirely.
 */
export function onsetPeriod(regions: readonly LossRegion[]): number | null {
  return regions.length === 0 ? null : regions[0][0].period;
}

// ------------------------------------------------------------------------------ scales

/** Maps data coordinates onto the drawing box. */
export interface CurveScale {
  readonly x: (period: number) => number;
  readonly y: (fill: number) => number;
}

/** The plot area inside the SVG, once the axis gutters are taken out. */
export interface CurveBox {
  readonly width: number;
  readonly height: number;
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

/**
 * A linear scale from (period, fill) onto the plot area.
 *
 * The y-axis is pinned to [0, 1] rather than fitted to the data, deliberately. A fill rate is a
 * proportion with a meaningful floor and ceiling, and auto-fitting would redraw the same disruption
 * as a cliff on one variant and a dip on another purely because their minima differ - which is the
 * one thing the comparison view exists to prevent.
 */
export function scaleFor(box: CurveBox, horizonPeriods: number): CurveScale {
  const plotWidth = box.width - box.left - box.right;
  const plotHeight = box.height - box.top - box.bottom;
  // A single-period run would divide by zero; it plots as one point at the left edge.
  const span = Math.max(1, horizonPeriods - 1);
  return {
    x: (period) => box.left + (period / span) * plotWidth,
    y: (fill) => box.top + (1 - clamp01(fill)) * plotHeight,
  };
}

/** An SVG polyline path through one series. */
export function linePath(
  points: readonly CurvePoint[],
  scale: CurveScale,
  pick: (point: CurvePoint) => number,
): string {
  if (points.length === 0) {
    return '';
  }
  return points
    .map((point, index) => `${index === 0 ? 'M' : 'L'}${scale.x(point.period)},${scale.y(pick(point))}`)
    .join(' ');
}

/**
 * One shaded region as a closed SVG polygon: out along the baseline, back along the disrupted curve.
 *
 * Closed with `Z` rather than left open, so a fill rule cannot be asked to guess where the boundary
 * runs between the last vertex and the first.
 */
export function regionPath(region: LossRegion, scale: CurveScale): string {
  if (region.length < 2) {
    return '';
  }
  const forward = region.map(
    (vertex, index) => `${index === 0 ? 'M' : 'L'}${scale.x(vertex.period)},${scale.y(vertex.upper)}`,
  );
  const backward = [...region]
    .reverse()
    .map((vertex) => `L${scale.x(vertex.period)},${scale.y(vertex.lower)}`);
  return [...forward, ...backward, 'Z'].join(' ');
}

/**
 * Where to put x-axis ticks: whole periods, thinned to about `target` of them.
 *
 * Whole periods rather than round numbers of the underlying unit, because the x-axis *is* a period
 * index - the label on the axis names the unit, and a tick at "period 7.5" would name
 * something the simulation has no state for.
 */
export function periodTicks(horizonPeriods: number, target = 8): number[] {
  if (horizonPeriods <= 1) {
    return [0];
  }
  const step = Math.max(1, Math.ceil((horizonPeriods - 1) / target));
  const ticks: number[] = [];
  for (let period = 0; period < horizonPeriods; period += step) {
    ticks.push(period);
  }
  const last = horizonPeriods - 1;
  if (ticks[ticks.length - 1] !== last) {
    ticks.push(last);
  }
  return ticks;
}

/** Fill-rate gridlines. Fixed, because the y-axis is fixed - see {@link scaleFor}. */
export const FILL_TICKS: readonly number[] = [0, 0.25, 0.5, 0.75, 1];

function clamp01(value: number): number {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.max(0, Math.min(1, value));
}
