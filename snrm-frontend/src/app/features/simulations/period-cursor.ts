import { ElementSeriesIndex } from '../../core/element-series';
import { Id } from '../../core/models';
import { NetworkSeries, valueAtPeriod } from '../network-editor/network-series';
import {
  fillLevel,
  seriesMax,
  unavailableOpacity,
  valueAt,
} from '../network-editor/playback-channels';

/**
 * The results dashboard's shared period cursor, as arithmetic (FR-22).
 *
 * > "A period cursor - scrub and step controls shared by every chart on the page - moves back and
 * > forth through the horizon: every chart carries the cursor line, every per-period figure restates
 * > itself at the cursor's period, and the miniature tints availability and fill at that period so a
 * > disruption's footprint is visible while it is scrubbed."
 *
 * Free of Angular and of the DOM, like `curve-geometry.ts`, `mini-map-layout.ts` and
 * `element-charts.ts` beside it. What is pinned here is the part that decides whether two surfaces
 * agree: where the cursor is allowed to be, where it starts, and what each of the page's readings
 * says *at* it. `period-cursor.spec.ts` pins all three against
 * `../snrm-backend/samples/four-echelon-playback/README.md` §6.4 and §6.5.
 *
 * ## It is a cursor and not a clock, and that is the whole design
 *
 * The distinction is deliberate: this is a cursor, not a clock. The dashboard
 * navigates a run; animating one is playback, which belongs to the editor canvas, and it
 * is one `requestAnimationFrame` loop in the whole application, not two. So there is no `advance`
 * function here, no elapsed time, no speed and no `finished` flag - every one of which
 * `network-editor/playback-clock.ts` has, because that module *is* the other thing. What is left is
 * a position a reader moves, which is why {@link clampPeriod} is the only movement rule and why it
 * is total: a cursor never lands outside the run.
 *
 * ## Nothing here re-derives a series
 *
 * The network figures come from `network-editor/network-series.ts` and the tints from
 * `network-editor/playback-channels.ts` - both pure, both specced, both already the answer to
 * "what does this quantity read at period *t*" on the editor's own surfaces. This module chooses
 * *which* readings the dashboard prints and hands the arithmetic to them, because a second
 * implementation of "on-hand at period 14" is a second chance for the canvas and the dashboard to
 * disagree about one run. That is the `disruption-overlay.ts → timeline.ts` precedent this feature
 * already takes for the palette and the step geometry, applied twice more.
 *
 * ## Cheap per step, expensive once
 *
 * {@link fillScales} walks every node's whole horizon and {@link cursorTints} walks none of it - one
 * lookup per element. The split is deliberate and is `playback-channels.indexElements`' own: a
 * normalising maximum is a property of the horizon and cannot change while a completed run is on
 * screen, so recomputing it per cursor step would be `O(horizon)` work per element to arrive at the
 * same number. Stepping a period must cost one pass over the elements and nothing more.
 */

// ------------------------------------------------------------------------------ where it may be

/**
 * A period the cursor may stand on: a whole index in `[0, horizonPeriods − 1]`.
 *
 * Floor rather than round, matching `playback-clock.periodOf`: a period is a half-open interval, and
 * a cursor dropped at 3.7 is in period 3. A horizon of zero - a run that has not written its series
 * - answers 0, so a caller never has to branch before clamping; the transport is not rendered at all
 * in that state (see `RunResultsStore.hasCursor`).
 */
export function clampPeriod(period: number, horizonPeriods: number): number {
  const last = Number.isFinite(horizonPeriods) ? Math.max(0, Math.floor(horizonPeriods) - 1) : 0;
  // `NaN` is the one input with no position to clamp - it compares false against every bound, so it
  // is answered rather than clamped. An infinity has a direction and therefore has an end to land on.
  if (Number.isNaN(period)) {
    return 0;
  }
  return Math.min(Math.max(0, Math.floor(period)), last);
}

/**
 * Where the cursor starts: the **last** period of the run.
 *
 * The end state is what a reader means by "the result" until they scrub. Every scalar on the page
 * is a horizon figure - the metric cards are means over the whole run, and a horizon scalar
 * has no per-period form - so a cursor opening at period 0 would put the page's per-period column
 * at the *warm-up* while its scalar column described the whole horizon, and the two would disagree
 * about what the page was showing before the reader had touched anything. Opening at the end also
 * makes the first gesture the useful one: scrubbing *back* into the disruption from the outcome it
 * produced.
 *
 * This is the opposite of `PlaybackStore`'s rewind-to-0, and for the opposite reason: playback is a
 * story that has to start at the beginning, and a cursor is a question asked of a finished run.
 */
export function defaultCursorPeriod(horizonPeriods: number): number {
  return clampPeriod(Number.MAX_SAFE_INTEGER, horizonPeriods);
}

/**
 * One step of the cursor: `period + delta`, clamped into the run.
 *
 * Defined on the **period index** rather than on any fractional position, as
 * `PlaybackStore.stepForward` is - there is no fractional position here to be symmetric about, which
 * is one more thing a cursor does not have to own.
 */
export function steppedPeriod(period: number, delta: number, horizonPeriods: number): number {
  const from = clampPeriod(period, horizonPeriods);
  const by = Number.isFinite(delta) ? Math.trunc(delta) : 0;
  return clampPeriod(from + by, horizonPeriods);
}

// -------------------------------------------------------------- the network scope, at the cursor

/**
 * What the network scope prints at the cursor's period (FR-19's figures, one page across).
 *
 * Every field is null where the run has no number for that period rather than 0, following
 * `network-series.valueAtPeriod`'s rule: these are printed into sentences, where a substituted
 * number is a claim.
 */
export interface NetworkFigures {
  /** The period these figures were read at - carried so a caller cannot label them with another. */
  readonly period: number;
  /**
   * Served over demanded, as `RUN_TIMESERIES` itself reads it.
   *
   * Taken from `NetworkSeries.fill` rather than recomputed as `served / demand`, and the difference
   * is a period nobody demanded anything in: the engine reads that as **fully served**
   * (`PeriodTrace.fillRate`, and `network-series.ts` follows it), so the curve draws 1 there. A
   * figure that printed a dash beside a curve drawn at 100% would be the same period reading two
   * ways on one screen - which is exactly the drift the shared cursor exists to prevent. The
   * quiet period is stated instead, by {@link noDemand}.
   */
  readonly fill: number | null;
  /** True where no demand arose in this period, so the fill rate above is a convention. */
  readonly noDemand: boolean;
  readonly served: number | null;
  readonly demand: number | null;
  /** This period's cost alone - fixed, variable, transport, holding and shortage. */
  readonly cost: number | null;
  /** Spent from the first period **through** this one; at the last period it is `TOTAL_COST`. */
  readonly costToCursor: number | null;
  /** Demand lost from the first period through this one. Lost, not backlogged. */
  readonly unmetToCursor: number | null;
  /** Whole-network end-of-period stock. `RUN_TIMESERIES` records no undisrupted twin for it. */
  readonly onHand: number | null;
  /** Whole-network in-transit quantity. Likewise no twin. */
  readonly inPipeline: number | null;
}

/**
 * One period of the run's own curve, as the dashboard's per-period figures.
 *
 * The cumulative rows are read straight out of the running totals `networkSeries` already built -
 * `cumulativeCost[t]` is by construction the total through period *t* (`runningTotal`'s inclusive
 * convention) - rather than re-summed here. Summing again would be a second implementation of the
 * same sum, free to disagree with the sparkline the editor's dashboard draws from the first one.
 */
export function networkFiguresAt(series: NetworkSeries, period: number): NetworkFigures {
  const at = (values: readonly number[]): number | null => valueAtPeriod(values, period);
  const demand = at(series.demand);
  return {
    period,
    fill: at(series.fill),
    noDemand: demand !== null && demand <= 0,
    served: at(series.served),
    demand,
    cost: at(series.cost),
    costToCursor: at(series.cumulativeCost),
    unmetToCursor: at(series.cumulativeUnmet),
    onHand: at(series.onHand),
    inPipeline: at(series.inPipeline),
  };
}

// ------------------------------------------------------------------ the miniature, at the cursor

/**
 * How full a node stands and how much availability it has lost, at one period.
 *
 * Both numbers are `playback-channels`', unchanged: {@link fillLevel} against this node's own
 * horizon maximum, and {@link unavailableOpacity} of this period's availability multiplier. What
 * differs from the canvas is only how they are *drawn* - Cytoscape takes a gradient-stop string and
 * an SVG dot takes a clip and an opacity - which is why this module shares the arithmetic and not
 * the styling.
 */
export interface NodeTint {
  /** `onHand / max over the horizon`, in [0,1] - the height the dot is filled to. */
  readonly fill: number;
  /** `0.35 × (1 − availability)` - the red halo's opacity. Zero for a fully available node. */
  readonly dim: number;
}

/** An arc's lost availability at one period. An arc has no stock, so it has no fill. */
export interface LinkTint {
  readonly dim: number;
}

/** Every element's tint at one period, keyed the way the miniature looks elements up. */
export interface CursorTints {
  readonly nodes: ReadonlyMap<Id, NodeTint>;
  readonly links: ReadonlyMap<Id, LinkTint>;
}

/**
 * No tints at all - a run whose element series has not been read, or that recorded none.
 *
 * A shared constant so a miniature drawn without tints is handed the same identity on every read,
 * and so that "untinted" is one state rather than an empty map somebody built. **Absent renders
 * absent**: an element with no entry keeps its ordinary drawing rather than one reading empty and
 * fully available, which is `applyPlayback`'s rule for the canvas.
 */
export const NO_TINTS: CursorTints = { nodes: new Map(), links: new Map() };

/**
 * Each node's normalising maximum - its largest on-hand over the whole horizon.
 *
 * Computed once per element series and never per step; see the module note. A node that never holds
 * stock keeps its 0 here, and {@link fillLevel} answers 0 for it rather than dividing by it.
 */
export function fillScales(index: ElementSeriesIndex | null): ReadonlyMap<Id, number> {
  const scales = new Map<Id, number>();
  if (!index) {
    return scales;
  }
  for (const [nodeId, series] of index.nodes) {
    scales.set(nodeId, seriesMax(series.onHand));
  }
  return scales;
}

/**
 * Every element's tint at one period - one lookup per element, no pass over any horizon.
 *
 * Rounded on the way out, and not cosmetically: these numbers become SVG attributes, and a fill that
 * changed from `0.3333333333333333` to `0.33333333333333337` between two periods would write a new
 * attribute value for a difference far below a device pixel - a repaint of an element with nothing
 * new to say. That is `gaugeStops`' own reason for rounding to whole percent, at the resolution this
 * surface has: two decimals of a fill is a fifth of a pixel on a 16-unit dot, and three of an
 * opacity is past what a screen renders.
 */
export function cursorTints(
  index: ElementSeriesIndex | null,
  scales: ReadonlyMap<Id, number>,
  period: number,
): CursorTints {
  if (!index) {
    return NO_TINTS;
  }
  const nodes = new Map<Id, NodeTint>();
  for (const [nodeId, series] of index.nodes) {
    nodes.set(nodeId, {
      fill: round(fillLevel(valueAt(series.onHand, period), scales.get(nodeId) ?? 0), 2),
      dim: round(unavailableOpacity(valueAt(series.availability, period)), 3),
    });
  }
  const links = new Map<Id, LinkTint>();
  for (const [linkId, series] of index.links) {
    links.set(linkId, { dim: round(unavailableOpacity(valueAt(series.availability, period)), 3) });
  }
  return { nodes, links };
}

function round(value: number, places: number): number {
  return Number.isFinite(value) ? Number(value.toFixed(places)) : 0;
}
