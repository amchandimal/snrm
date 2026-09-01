import { ElementTimeseries, Id, LinkTimeseries, NodeTimeseries } from '../../core/models';

/**
 * The arithmetic of the playback **channels** - what one period of a run looks like on the canvas
 * (FR-18).
 *
 * Free of Angular, of the DOM and of Cytoscape, like `playback-clock.ts` beside it and
 * `features/simulations/curve-geometry.ts` before that. The reason is the same one the charting rule
 * gives: the mapping from a simulated number to a visual quantity is the part that has to
 * be right, and a pure function is the only version of it that can be pinned against hand-worked
 * figures. `playback-channels.spec.ts` pins every rule below against
 * `samples/four-echelon-playback/README.md` §6.5, which derives those figures by hand from the
 * per-period loop rather than from a run.
 *
 * ## Every channel is normalised per element, never across the network
 *
 * A node's gauge is its own stock against **its own** maximum over the horizon, and a link's ribbon
 * is its own flow against **its own** maximum. Normalising across the network instead would make one
 * large distribution centre flatten every other node to a sliver, and the thing playback exists to
 * show - *this* element filling and draining as material moves down the chain - would be invisible
 * on everything but the largest. The cost is stated rather than hidden: two nodes at full gauge are
 * not holding the same quantity, so the gauge answers "how full for it" and the tooltip and the
 * element series answer "how much".
 *
 * ## Absent is not zero here either
 *
 * An element the run has no series for gets **no** playback channel at all (the canvas leaves it in
 * its ordinary styling) rather than a channel reading zero - the same rule the metric cards follow.
 * {@link valueAt} answers 0 only for a period past the end of a series that does exist,
 * which is a clamp, not a claim.
 */

/** Edge width in pixels at zero flow - and the width every uncapped-but-idle arc renders at. */
export const FLOW_WIDTH_MIN = 2;

/** Edge width in pixels at an arc's own busiest period. */
export const FLOW_WIDTH_MAX = 9;

/**
 * Opacity of an arc carrying nothing this period.
 *
 * An idle arc is still part of the network, so it is faded rather than hidden: a link that vanished
 * for a period would look like a link that was deleted, and the structure has to stay readable while
 * the flow changes on top of it.
 */
export const IDLE_LINK_OPACITY = 0.4;

/** Opacity a fully unavailable element's disruption underlay reaches (FR-16 colours). */
export const UNAVAILABLE_UNDERLAY_MAX = 0.35;

/** One node's series with the constant its gauge is normalised against. */
export interface NodeChannel {
  readonly series: NodeTimeseries;
  /** Largest `onHand` this node reaches over the whole horizon. Zero for a node that never holds. */
  readonly maxOnHand: number;
}

/** One link's series with the constant its ribbon is normalised against. */
export interface LinkChannel {
  readonly series: LinkTimeseries;
  /** Largest `flow` this arc carries over the whole horizon. Zero for an arc that never moves. */
  readonly maxFlow: number;
}

/** A run's per-element series, keyed the way the canvas looks elements up. */
export interface PlaybackElements {
  readonly nodes: ReadonlyMap<Id, NodeChannel>;
  readonly links: ReadonlyMap<Id, LinkChannel>;
}

/**
 * Index a run's element series by id, taking each element's normalising maximum once.
 *
 * The maxima are computed here, at load, and not per repaint: they are properties of the whole
 * horizon and cannot change while a completed run is on screen, so recomputing them on
 * every period would be `O(horizon)` work per element per period to arrive at the same number.
 */
export function indexElements(series: ElementTimeseries): PlaybackElements {
  const nodes = new Map<Id, NodeChannel>();
  for (const node of series.nodes) {
    nodes.set(node.nodeId, { series: node, maxOnHand: seriesMax(node.onHand) });
  }
  const links = new Map<Id, LinkChannel>();
  for (const link of series.links) {
    links.set(link.linkId, { series: link, maxFlow: seriesMax(link.flow) });
  }
  return { nodes, links };
}

/**
 * The largest finite value in a series, or 0 for an empty or entirely non-finite one.
 *
 * Never negative: every quantity these channels normalise against is a stock or a flow, and a
 * negative maximum would invert the scale rather than reporting the impossible number that produced
 * it.
 */
export function seriesMax(values: readonly number[] | undefined): number {
  if (!values?.length) {
    return 0;
  }
  let max = 0;
  for (const value of values) {
    if (Number.isFinite(value) && value > max) {
      max = value;
    }
  }
  return max;
}

/**
 * The value of a series at a period, clamped to the series it has.
 *
 * A run whose element series is shorter than the transport bar's horizon cannot happen through the
 * API - both come from the same run - but a clamp is cheaper than the alternative of a channel
 * reading `undefined` into a Cytoscape style, which renders as nothing at all rather than as an
 * error anybody would notice.
 */
export function valueAt(values: readonly number[] | undefined, period: number): number {
  if (!values?.length) {
    return 0;
  }
  const index = Math.min(Math.max(0, Math.floor(period)), values.length - 1);
  const value = values[index];
  return Number.isFinite(value) ? value : 0;
}

/**
 * How full a node's gauge stands this period, in [0,1] - `onHand / max over the horizon`.
 *
 * **A node whose maximum is 0 reads 0**, which is the one place the per-element normalisation needs
 * a stated answer: a node that never holds stock has no scale of its own, and dividing by its
 * maximum would be `0/0`. Empty is the honest picture - it held nothing in every period, including
 * this one.
 */
export function fillLevel(onHand: number, maxOnHand: number): number {
  if (!Number.isFinite(onHand) || !Number.isFinite(maxOnHand) || maxOnHand <= 0) {
    return 0;
  }
  return Math.min(1, Math.max(0, onHand / maxOnHand));
}

/**
 * An arc's width this period, in pixels: linear from {@link FLOW_WIDTH_MIN} at no flow to
 * {@link FLOW_WIDTH_MAX} at that arc's own busiest period.
 *
 * Linear rather than logarithmic on purpose. The quantity is a rate over one period on one arc, and
 * the comparison the eye is being asked to make is with the *same arc a moment ago* - for which a
 * doubled flow should look twice as wide. (The pace ladder's default is chosen by ratio for the
 * opposite reason: there the quantity is a duration, and half and double are equally wrong.)
 *
 * The floor is not cosmetic: an arc drawn at zero width is an arc that is not drawn, and a network
 * that loses its structure whenever nothing moves is unreadable exactly when a stall is the finding.
 */
export function flowWidth(flow: number, maxFlow: number): number {
  if (!Number.isFinite(flow) || !Number.isFinite(maxFlow) || maxFlow <= 0 || flow <= 0) {
    return FLOW_WIDTH_MIN;
  }
  const share = Math.min(1, flow / maxFlow);
  return FLOW_WIDTH_MIN + (FLOW_WIDTH_MAX - FLOW_WIDTH_MIN) * share;
}

/**
 * The disruption underlay's opacity for an element at this period's availability (FR-16).
 *
 * `0.35 × (1 − availability)`, so a fully available element carries **no** halo and a dark one
 * carries the same weight the scenario-authoring halo has. That is deliberate: while playback runs,
 * this *is* the halo - the underlay has one owner at a time (see `graph-canvas.applyPlayback`), and
 * the static badge saying "a scenario strikes this element somewhere in the horizon" would otherwise
 * sit on top of the temporal answer to the sharper question, "is it struck **now**".
 *
 * An availability the engine never produced - negative, above 1, `NaN` - clamps rather than throws:
 * a canvas is not the place to discover a bad number, and the element series has its own tests.
 */
export function unavailableOpacity(availability: number): number {
  if (!Number.isFinite(availability)) {
    return 0;
  }
  const clamped = Math.min(1, Math.max(0, availability));
  return UNAVAILABLE_UNDERLAY_MAX * (1 - clamped);
}

/**
 * The gradient stop positions that draw a gauge filled to `fill`, as Cytoscape writes them.
 *
 * Four stops rather than two, so the boundary is a **line** rather than a fade: `0% L% L% 100%`
 * against the colour list of {@link gaugeColours} puts the deeper shade below the line and the type
 * colour above it. A two-stop gradient would blend the two across the whole node, which reads as a
 * shading style rather than as a quantity, and two nodes at 40% and 60% would be indistinguishable.
 *
 * Rounded to whole percent because that is the resolution of a 46-pixel node: a tenth of a percent
 * is a fifth of a pixel, and writing it would change the data - and so repaint the element - on
 * periods where nothing visible moved.
 */
export function gaugeStops(fill: number): string {
  const percent = Math.round(Math.min(1, Math.max(0, Number.isFinite(fill) ? fill : 0)) * 100);
  return `0% ${percent}% ${percent}% 100%`;
}

/**
 * The gradient stop colours for a node's gauge: its own accent below the line, its own type colour
 * above it.
 *
 * **The hue stays the type** (node colour encodes the echelon), so the gauge is a second
 * reading of the same colour rather than a new one. `accent` is already the darkened form of
 * `colour` that the border and the palette tile use (`echelon-rules.ts`), which is why the "stronger
 * shade" needs no colour arithmetic here and cannot drift from the palette.
 */
export function gaugeColours(colour: string, accent: string): string {
  return `${accent} ${accent} ${colour} ${colour}`;
}
