/**
 * The element inspector's sparklines, as arithmetic (FR-18).
 *
 * Free of Angular and of the DOM, like `playback-clock.ts` and `playback-channels.ts` beside it and
 * `features/simulations/curve-geometry.ts` before them. The reason is the charting rule
 * applied one size down: the mapping from a simulated series to a shape is the part that has to be
 * right, and a pure function is the only version of it that can be pinned against hand-worked
 * numbers. `sparkline-geometry.spec.ts` pins every path string below.
 *
 * ## The line is stepwise, because the engine is discrete-time
 *
 * The engine's loop produces one number per period and nothing in between, so a period is drawn as a
 * **band** of the plot rather than as a point on it: sample `t` owns the slot from `t × slot` to
 * `(t + 1) × slot` and is flat across it, with a vertical riser where the value changes. Joining the
 * samples with sloped segments would draw an inventory level halfway through period 3 - a number no
 * replication produced (the same rule `PlaybackStore` states for the clock: playback is stepwise,
 * never interpolated).
 *
 * That is also why the cursor sits at the **centre** of its band: it marks the period the clock is
 * on, and a period is an interval.
 *
 * ## A null breaks the line; it is never drawn as 0
 *
 * `NodeTimeseries.inboundLead` carries nulls and a null is a claim - nothing was dispatched toward
 * that node in that period. A 0 would say something was and arrived instantly, and a line
 * dipping to the axis is exactly how a reader would read one. So each contiguous run of present
 * values is its **own path**, and the gap between two runs is drawn as nothing at all. This is the
 * "absent renders absent, never zero" rule in geometry.
 *
 * ## The scale fits the data, and the card prints the bounds
 *
 * Unlike the performance curve - whose y-axis is pinned to [0, 1] because a fill rate has a
 * meaningful floor and ceiling (`curve-geometry.scaleFor`) - a sparkline of a stock or a flow has
 * neither, and the quantity the reader is being shown is *change*. A steady state of 5 against a
 * zero-anchored axis is a flat line at a third height; against a fitted one it is a flat line, which
 * is the finding. The cost is that the floor is not zero, which is why the inspector prints the
 * range beside the line rather than leaving the scale implicit, and why both series share one range:
 * a baseline overlaid on a different scale would be a picture of nothing.
 */

/**
 * A per-period series, with the nullable entries the element series delivers.
 *
 * `readonly number[]` (`onHand`, `flow`) and `readonly (number | null)[]` (`inboundLead`,
 * `utilisation`) both satisfy it, so a caller never has to widen one before drawing it.
 */
export type SparkSeries = readonly (number | null | undefined)[];

/**
 * The drawing box in the SVG's own user units - a fixed viewBox, scaled by CSS.
 *
 * ## One pad, or four gutters
 *
 * {@link pad} is the whole box for a sparkline: a 40-pixel-high line beside a figure needs the same
 * small gutter on all four sides so a value at the minimum is not clipped by the border, and it has
 * no axes to leave room for.
 *
 * A **chart** does. The results dashboard draws these same step lines at chart size with a value
 * axis down the left and a period axis along the bottom (FR-22), and those gutters are not equal -
 * the left has to hold `12 000` and the bottom a row of period numbers. So each side may be stated
 * individually and falls back to {@link pad} where it is not. Every existing caller states `pad`
 * alone and is therefore drawn exactly as before, which is what lets `sparkline-geometry.spec.ts`
 * keep pinning the same hand-worked path strings.
 */
export interface SparkBox {
  readonly width: number;
  readonly height: number;
  /** Gutter on all four sides, so a line at the minimum is not clipped by the border. */
  readonly pad: number;
  /** Left gutter - the value axis's room. Defaults to {@link pad}. */
  readonly left?: number;
  /** Right gutter. Defaults to {@link pad}. */
  readonly right?: number;
  /** Top gutter. Defaults to {@link pad}. */
  readonly top?: number;
  /** Bottom gutter - the period axis's room. Defaults to {@link pad}. */
  readonly bottom?: number;
}

/** The value range a sparkline is drawn against - the two numbers the card prints. */
export interface SparkRange {
  readonly min: number;
  readonly max: number;
}

/**
 * Everything needed to place something else on a drawn sparkline: the reference line of a mean, the
 * cursor of the current period.
 *
 * Carried on the result rather than taken as a second `SparkBox` argument, deliberately: a caller
 * that passed a different box to {@link valueY} than to {@link sparkline} would draw a line that
 * missed its own curve, and there is no way to notice that in a 40-pixel-high graphic.
 */
export interface SparkFrame extends SparkRange {
  /** Periods on the x-axis - the longer of the two series. */
  readonly count: number;
  /** Width of one period's band. */
  readonly slot: number;
  readonly left: number;
  readonly top: number;
  readonly plotHeight: number;
}

export interface Sparkline extends SparkFrame {
  /** One path per contiguous run of present values. Empty where the series is. */
  readonly paths: readonly string[];
  /** The same for the overlaid baseline series, or empty when none was given. */
  readonly baselinePaths: readonly string[];
  /** True when there is nothing to draw - no periods, or no finite value in either series. */
  readonly empty: boolean;
}

/**
 * A series as step paths, with an optional baseline overlaid on the same scale.
 *
 * Both series share one range, one slot width and one plot area; see the module note.
 */
export function sparkline(values: SparkSeries, box: SparkBox, baseline?: SparkSeries): Sparkline {
  const count = Math.max(values.length, baseline?.length ?? 0);
  const range = seriesRange(values, baseline);
  const left = box.left ?? box.pad;
  const right = box.right ?? box.pad;
  const top = box.top ?? box.pad;
  const bottom = box.bottom ?? box.pad;
  const plotHeight = Math.max(0, box.height - top - bottom);
  if (count === 0 || range === null) {
    return {
      min: 0,
      max: 0,
      count,
      slot: 0,
      left,
      top,
      plotHeight,
      paths: [],
      baselinePaths: [],
      empty: true,
    };
  }
  const frame: SparkFrame = {
    ...range,
    count,
    slot: Math.max(0, box.width - left - right) / count,
    left,
    top,
    plotHeight,
  };
  return {
    ...frame,
    paths: stepPaths(values, frame),
    baselinePaths: baseline ? stepPaths(baseline, frame) : [],
    empty: false,
  };
}

/**
 * The range both series are drawn against, or null when neither carries a finite value.
 *
 * Over **both** series, so an overlay that runs above the disrupted line is inside the box rather
 * than clipped to its top edge - the gap between the two is the whole reason the overlay is there.
 */
export function seriesRange(values: SparkSeries, baseline?: SparkSeries): SparkRange | null {
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  for (const series of baseline ? [values, baseline] : [values]) {
    for (const value of series) {
      if (typeof value === 'number' && Number.isFinite(value)) {
        min = Math.min(min, value);
        max = Math.max(max, value);
      }
    }
  }
  return min === Number.POSITIVE_INFINITY ? null : { min, max };
}

/**
 * Where a value sits vertically - the mean's reference line, and every vertex of every path.
 *
 * **A flat series is drawn through the middle.** Its range is degenerate, so there is no fraction to
 * take; the top would read as a maximum and the bottom as an empty node, and both are claims the
 * series does not make.
 */
export function valueY(value: number, frame: SparkFrame): number {
  const span = frame.max - frame.min;
  if (!Number.isFinite(value) || span <= 0) {
    return coordinate(frame.top + frame.plotHeight / 2);
  }
  const share = Math.min(1, Math.max(0, (value - frame.min) / span));
  return coordinate(frame.top + (1 - share) * frame.plotHeight);
}

/**
 * The centre of a period's band - where the playback cursor stands.
 *
 * Clamped into the series, like `playback-channels.valueAt`: a cursor is a marker rather than a
 * measurement, and a canvas is not the place to discover an index out of range.
 */
export function cursorX(index: number, frame: SparkFrame): number {
  if (frame.count <= 0) {
    return coordinate(frame.left);
  }
  const clamped = Math.min(Math.max(0, Math.floor(index)), frame.count - 1);
  return coordinate(frame.left + (clamped + 0.5) * frame.slot);
}

// -------------------------------------------------------- the numbers printed beside the line

/**
 * The mean of a series over its whole horizon, or null when it carries no value at all.
 *
 * **Absent entries are skipped, not counted as zero** - the same rule the paths follow. The
 * inspector rounds this to 2 dp for display and draws it as a reference line through
 * {@link valueY}; both readings come from this one number, so the line cannot sit somewhere the
 * printed figure does not.
 */
export function seriesMean(values: SparkSeries): number | null {
  let total = 0;
  let count = 0;
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      total += value;
      count += 1;
    }
  }
  return count === 0 ? null : total / count;
}

/**
 * A mean weighted by a parallel series - the flow-weighted inbound lead time.
 *
 * ```text
 *   Σ_t  value(t) × weight(t)   /   Σ_t weight(t)
 * ```
 *
 * **Weighted rather than plain, because the plain mean of a lead time is not a lead time anyone
 * experienced.** `inboundLead` is already dispatch-weighted *within* a period; averaging
 * those per-period figures without weighting them by what each period dispatched would give a
 * period that moved one unit the same say as a period that moved a hundred.
 *
 * A period the value is absent for contributes nothing - neither to the total nor to the divisor -
 * so a node that was never dispatched to answers **null**, which the inspector renders as absent
 * rather than as an instantaneous 0.
 */
export function weightedMean(values: SparkSeries, weights: SparkSeries): number | null {
  let total = 0;
  let weight = 0;
  const count = Math.min(values.length, weights.length);
  for (let index = 0; index < count; index++) {
    const value = values[index];
    const share = weights[index];
    if (
      typeof value !== 'number' ||
      !Number.isFinite(value) ||
      typeof share !== 'number' ||
      !Number.isFinite(share) ||
      share <= 0
    ) {
      continue;
    }
    total += value * share;
    weight += share;
  }
  return weight === 0 ? null : total / weight;
}

/**
 * The running total through a period, inclusive - the inspector's "lost so far" counter.
 *
 * Inclusive because the number stands beside this period's own figure: a stockout of 10 shown with a
 * cumulative total that excludes it would read as the loss before the one on screen.
 */
export function cumulativeThrough(values: SparkSeries, index: number): number {
  let total = 0;
  const last = Math.min(Math.floor(index), values.length - 1);
  for (let period = 0; period <= last; period++) {
    const value = values[period];
    if (typeof value === 'number' && Number.isFinite(value)) {
      total += value;
    }
  }
  return total;
}

// ------------------------------------------------------------------------------- internals

/**
 * One path per contiguous run of present values, each a chain of horizontal treads and vertical
 * risers.
 *
 * Consecutive identical points are dropped rather than written: a flat stretch would otherwise emit
 * a zero-length riser at every period boundary, which is invisible on screen and noise in a spec.
 * A run that survives with fewer than two points has no width and is dropped whole - the same rule
 * `curve-geometry.lossRegions` applies to a single-vertex region.
 */
function stepPaths(values: SparkSeries, frame: SparkFrame): string[] {
  const paths: string[] = [];
  let points: string[] = [];

  const push = (x: number, y: number): void => {
    const point = `${coordinate(x)},${coordinate(y)}`;
    if (points[points.length - 1] !== point) {
      points.push(point);
    }
  };
  const flush = (): void => {
    if (points.length >= 2) {
      paths.push(`M${points[0]}${points.slice(1).map((point) => ` L${point}`).join('')}`);
    }
    points = [];
  };

  for (let period = 0; period < frame.count; period++) {
    const value = values[period];
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      flush();
      continue;
    }
    const y = valueY(value, frame);
    push(frame.left + period * frame.slot, y);
    push(frame.left + (period + 1) * frame.slot, y);
  }
  flush();
  return paths;
}

/**
 * A coordinate as it is written into the path.
 *
 * Two decimals is a hundredth of a user unit on a box a few hundred wide - well under a device
 * pixel - and it keeps `13.000000000000002` out of both the DOM and the spec. Rounded here rather
 * than at the template so {@link valueY} and the paths cannot disagree about where a value sits.
 */
function coordinate(value: number): number {
  return Number.isFinite(value) ? Number(value.toFixed(2)) : 0;
}
