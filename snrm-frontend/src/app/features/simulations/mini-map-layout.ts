import { Id, NetworkLink, NetworkNode, NodeType } from '../../core/models';
import { ECHELON_RANK, nodeTypeProfile } from '../network-editor/echelon-rules';

/**
 * The results dashboard's network inspector, as arithmetic (FR-22).
 *
 * > "Its top-left corner holds the **network inspector**: a read-only miniature of the run's network,
 * > hand-drawn SVG under the same rule as every chart in the application - Cytoscape stays out of the
 * > dashboard bundle, and a miniature needs a click target per element, not an editing surface. It
 * > draws at the stored `pos_x`/`pos_y`; elements without coordinates fall back to a simple
 * > echelon-layered arrangement, computed client-side and never persisted."
 *
 * Free of Angular and of the DOM, like `curve-geometry.ts` beside it and
 * `network-editor/sparkline-geometry.ts` one feature across. The reason is the charting rule
 * applied to a graph: the mapping from stored coordinates to a picture is the part that has
 * to be right, and a pure function is the only version of it that can be pinned against hand-worked
 * numbers. `mini-map-layout.spec.ts` pins every rule below.
 *
 * ## Why the palette is imported from the editor rather than lifted to `shared/`
 *
 * `nodeTypeProfile` and `ECHELON_RANK` come from `features/network-editor/echelon-rules.ts` - a
 * cross-feature import, and the same one `network-editor/disruption-overlay.ts` makes into
 * `scenario-builder/timeline.ts`. The precedent applies for the precedent's reason: a *reading* of
 * another feature's pure, specced module, taken because two surfaces must agree about one fact. There
 * a window could not read "periods 28–38" on the canvas and "periods 28–39" on the Gantt chart; here
 * a PLANT cannot be purple on the canvas and blue on the miniature, because the miniature exists to
 * be recognised as the network the reader arranged.
 *
 * Lifting was considered and rejected in both available shapes. Moving `echelon-rules.ts` whole into
 * `core/` would move link validity, node-type defaults and the auto-naming rule with it - editor
 * gestures, none of which this file or any other feature reads; `core/` holds what two features
 * *both* use (`lever-changes.ts`, `run-discard.ts`, `time-units.ts`), and this would be one reader of
 * one fifth of a module. Splitting `NODE_TYPE_PROFILES` out into a palette module of its own is the
 * closer call, and it loses on the documentation: `echelon-rules.spec.ts` walks the palette beside
 * the rules it belongs to, `playback-channels.gaugeColours` argues from the accent being "already the
 * darkened form … that the border and the palette tile use". Both references would have to be
 * re-aimed to gain nothing this import does not already
 * give. `mini-map-layout.spec.ts` asserts the colours against `nodeTypeProfile` itself, exactly as
 * `disruption-overlay.spec.ts` asserts against `placeBar` - a shared fact pinned at the seam rather
 * than transcribed.
 *
 * ## The arrangement is chosen for the whole graph, not per node
 *
 * Elements without coordinates fall back to an echelon-layered arrangement. That
 * decision is taken **once, for the network**: stored coordinates when every node carries both, and
 * the echelon layout otherwise. Mixing them would place a synthesised position in the same picture as
 * a researcher's own, and the two are not in the same space - a node laid out at echelon column 2
 * against neighbours stored at canvas x = 1840 lands on top of something or a screen away from
 * everything, and a reader cannot tell which nodes were placed and which were guessed. The panel says
 * which arrangement it drew, for the same reason.
 *
 * Neither arrangement is ever written back: this is a drawing of a frozen network, and the
 * dashboard has no write path to `PATCH /networks/{id}/nodes/positions` at all.
 *
 * ## The seam left for the period cursor (FR-22, next session)
 *
 * Every node and link carries its **element id**, and nothing here reads a period. The cursor's
 * "tints availability and fill at the cursor's period" is therefore a second pass over this same
 * layout - one lookup per element into the run's element series - and needs no new geometry and no
 * change to this module.
 *
 * ## The miniature draws *a* network, not only the run's (FR-25)
 *
 * Nothing in this module ever knew about a run - it takes nodes, links and a box - which is why the
 * side-by-side view of FR-25 draws its panes with it unchanged. What did know was the component
 * above it, and that is where the generalisation was made: `network-inspector` now takes its
 * network, its selection and its tints as inputs, and the results dashboard passes its own. See that
 * component's note. {@link MiniMapSelection} moved here as part of it, because the selection is a
 * fact about the drawing rather than about the dashboard.
 */

/** The drawing box in the SVG's own user units - a fixed viewBox, scaled by CSS. */
export interface MiniMapBox {
  readonly width: number;
  readonly height: number;
  /** Gutter on all four sides. Sized so a node dot at the edge of the fit is not clipped. */
  readonly pad: number;
  /** Radius of a node dot. Links are trimmed to it, so an arrow head lands outside the target. */
  readonly nodeRadius: number;
}

/** One node, placed and coloured. */
export interface MiniMapNode {
  readonly id: Id;
  readonly name: string;
  readonly type: NodeType;
  readonly x: number;
  readonly y: number;
  /** The palette's fill for this type - `nodeTypeProfile(type).colour`. */
  readonly colour: string;
  /** Its darkened form, used for the border, as everywhere else in the application. */
  readonly accent: string;
}

/** One arc, trimmed off both node dots, with the arrow head that states its direction. */
export interface MiniMapLink {
  readonly id: Id;
  readonly sourceNodeId: Id;
  readonly targetNodeId: Id;
  readonly x1: number;
  readonly y1: number;
  readonly x2: number;
  readonly y2: number;
  /**
   * The arrow head as an SVG `points` list, or **null** where there is no room to draw one.
   *
   * Null happens when two nodes sit on top of each other - possible with stored coordinates, since
   * nothing stops a researcher dropping one node on another. The line is still drawn (degenerate,
   * therefore invisible) and the arc is still selectable through its row in the network scope; what
   * it must not do is emit a triangle whose direction is decided by a division by zero.
   */
  readonly arrow: string | null;
  /** `SUP-1 → PLANT-1`, for the title and the accessible name. */
  readonly label: string;
}

/** Which of the two arrangements produced the picture - the panel states it. */
export type MiniMapArrangement =
  /** Every node carried `posX` and `posY`; this is the layout the researcher arranged. */
  | 'stored'
  /** At least one node had no coordinates, so the whole graph is laid out by echelon. */
  | 'echelon'
  /** Nothing to draw. */
  | 'empty';

export interface MiniMap {
  readonly nodes: readonly MiniMapNode[];
  readonly links: readonly MiniMapLink[];
  readonly arrangement: MiniMapArrangement;
  readonly empty: boolean;
}

/**
 * What one miniature has selected: nothing in particular, one node, or one arc.
 *
 * Declared beside the drawing rather than in either reader, because it is what a *picture of a
 * network* can have selected and both surfaces that draw one mean the same three things by it. The
 * results dashboard's `DashboardScope` is this type - the page's scope and the miniature's selection
 * are one signal there (FR-22) - and the side-by-side panes resolve the shared by-name
 * selection of FR-25 into one of these per pane.
 *
 * `network` rather than `none`: on the dashboard it is the whole-network scope, which is a subject
 * and not an absence, and a name that reads as "nothing" on one surface and "everything" on the
 * other would be a type saying two things.
 */
export type MiniMapSelection =
  | { readonly kind: 'network' }
  | { readonly kind: 'node'; readonly id: Id }
  | { readonly kind: 'link'; readonly id: Id };

/** The whole network - what an empty-space click selects, and what a miniature opens on. */
export const WHOLE_NETWORK: MiniMapSelection = { kind: 'network' };

/** Length of the arrow head along the arc, in user units. */
const ARROW_LENGTH = 7;

/** Half-width of the arrow head across the arc. */
const ARROW_HALF_WIDTH = 3.5;

/** Gap between a node's edge and the line that leaves or meets it. */
const NODE_GAP = 1.5;

/** A node as this module needs it - every field of `NetworkNode` it reads, and no more. */
type PlaceableNode = Pick<NetworkNode, 'id' | 'name' | 'type' | 'posX' | 'posY'>;

/** A link as this module needs it. */
type PlaceableLink = Pick<NetworkLink, 'id' | 'sourceNodeId' | 'targetNodeId'>;

/** A position before it is fitted to the box - canvas coordinates, or echelon column and row. */
interface RawPosition {
  readonly x: number;
  readonly y: number;
}

/**
 * The whole miniature: nodes placed and coloured, arcs trimmed and pointed.
 *
 * A link whose endpoints are not both in `nodes` is dropped rather than drawn to nowhere. It cannot
 * happen through the API - both come from the same network - but the two arrive in two responses,
 * and a half-loaded pair must not throw inside a view update (the defect
 * `results-dashboard.component.html` records against `record.params`).
 */
export function miniMap(
  nodes: readonly PlaceableNode[],
  links: readonly PlaceableLink[],
  box: MiniMapBox,
): MiniMap {
  if (nodes.length === 0) {
    return { nodes: [], links: [], arrangement: 'empty', empty: true };
  }

  const stored = hasStoredCoordinates(nodes);
  const raw = stored ? storedPositions(nodes) : echelonPositions(nodes);
  const placed = fit(nodes, raw, box);
  const byId = new Map(placed.map((node) => [node.id, node]));

  const arcs: MiniMapLink[] = [];
  for (const link of links) {
    const source = byId.get(link.sourceNodeId);
    const target = byId.get(link.targetNodeId);
    if (!source || !target) {
      continue;
    }
    arcs.push(arc(link, source, target, box));
  }

  return {
    nodes: placed,
    links: arcs,
    arrangement: stored ? 'stored' : 'echelon',
    empty: false,
  };
}

/**
 * Whether every node carries both coordinates - the test the arrangement is chosen by.
 *
 * **Both, and for every node.** A node with an x and no y is not half placed; it is a row the
 * importer wrote without a layout, and the editor's own auto-layout is what usually fills
 * them in. One such node makes the stored positions unusable as a set, per the module note.
 */
export function hasStoredCoordinates(nodes: readonly PlaceableNode[]): boolean {
  return nodes.every((node) => node.posX !== null && node.posY !== null);
}

/**
 * The fallback arrangement: one column per echelon, in the canonical flow order.
 *
 * `ECHELON_RANK` is what makes the columns the *chain* - SUPPLIER, PLANT, DC, CUSTOMER, left to
 * right - rather than four arbitrary groups, which is the whole value of the fallback: a reader who
 * cannot recognise the layout can still read the direction of flow. Rows within a column are ordered
 * by name so that redrawing the same network twice gives the same picture; a Map's insertion order
 * would make it depend on the order the API happened to answer in.
 *
 * Each column is centred on the middle row rather than starting at the top, so a network with one
 * supplier and six customers draws as a fan instead of a staircase.
 */
export function echelonPositions(nodes: readonly PlaceableNode[]): ReadonlyMap<Id, RawPosition> {
  const columns = new Map<number, PlaceableNode[]>();
  for (const node of nodes) {
    const rank = ECHELON_RANK[node.type];
    const column = columns.get(rank);
    if (column) {
      column.push(node);
    } else {
      columns.set(rank, [node]);
    }
  }

  const positions = new Map<Id, RawPosition>();
  for (const [rank, column] of columns) {
    const ordered = [...column].sort(
      (a, b) => a.name.localeCompare(b.name) || a.id - b.id,
    );
    const offset = (ordered.length - 1) / 2;
    ordered.forEach((node, index) => {
      positions.set(node.id, { x: rank, y: index - offset });
    });
  }
  return positions;
}

/** The stored canvas coordinates, which {@link hasStoredCoordinates} has already vouched for. */
function storedPositions(nodes: readonly PlaceableNode[]): ReadonlyMap<Id, RawPosition> {
  const positions = new Map<Id, RawPosition>();
  for (const node of nodes) {
    positions.set(node.id, { x: node.posX ?? 0, y: node.posY ?? 0 });
  }
  return positions;
}

/**
 * Fit the raw positions into the box, **preserving the aspect ratio**.
 *
 * One scale for both axes, not one each. Stretching each axis to fill the panel would redraw a wide
 * flat chain as a square and a tall one as a square too, and the miniature would stop being
 * recognisable as the canvas it copies - which is the only thing it is for. The fitted bounding box
 * is then centred in the panel, so the unused axis loses its slack symmetrically.
 *
 * Three degenerate cases have stated answers rather than divisions by zero: a single node, every node
 * on one vertical line, and every node on one horizontal line. In each, the degenerate axis is
 * centred and the other is fitted as usual.
 */
function fit(
  nodes: readonly PlaceableNode[],
  raw: ReadonlyMap<Id, RawPosition>,
  box: MiniMapBox,
): readonly MiniMapNode[] {
  const inner = {
    left: box.pad + box.nodeRadius,
    top: box.pad + box.nodeRadius,
    width: Math.max(0, box.width - 2 * (box.pad + box.nodeRadius)),
    height: Math.max(0, box.height - 2 * (box.pad + box.nodeRadius)),
  };

  let minX = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  for (const node of nodes) {
    const position = raw.get(node.id);
    if (!position || !Number.isFinite(position.x) || !Number.isFinite(position.y)) {
      continue;
    }
    minX = Math.min(minX, position.x);
    maxX = Math.max(maxX, position.x);
    minY = Math.min(minY, position.y);
    maxY = Math.max(maxY, position.y);
  }
  if (minX === Number.POSITIVE_INFINITY) {
    minX = maxX = minY = maxY = 0;
  }

  const spanX = maxX - minX;
  const spanY = maxY - minY;
  const scaleX = spanX > 0 ? inner.width / spanX : Number.POSITIVE_INFINITY;
  const scaleY = spanY > 0 ? inner.height / spanY : Number.POSITIVE_INFINITY;
  const scale = Number.isFinite(Math.min(scaleX, scaleY)) ? Math.min(scaleX, scaleY) : 0;

  // Centre what the uniform scale did not fill.
  const originX = inner.left + (inner.width - spanX * scale) / 2;
  const originY = inner.top + (inner.height - spanY * scale) / 2;

  return nodes.map((node) => {
    const position = raw.get(node.id) ?? { x: minX, y: minY };
    const profile = nodeTypeProfile(node.type);
    return {
      id: node.id,
      name: node.name,
      type: node.type,
      x: round2(originX + (position.x - minX) * scale),
      y: round2(originY + (position.y - minY) * scale),
      colour: profile.colour,
      accent: profile.accent,
    };
  });
}

/**
 * One arc: a line trimmed off both dots, with a triangle at the target end.
 *
 * **The direction is drawn, not implied.** Arcs are directed and the whole reading of the
 * network depends on which way material moves, so the miniature states it the way the canvas does -
 * at the head. Trimming at the source as well is what stops the line emerging from under the dot at
 * an angle that reads as a second arc.
 */
function arc(
  link: PlaceableLink,
  source: MiniMapNode,
  target: MiniMapNode,
  box: MiniMapBox,
): MiniMapLink {
  const dx = target.x - source.x;
  const dy = target.y - source.y;
  const length = Math.hypot(dx, dy);
  const label = `${source.name} → ${target.name}`;
  const trim = box.nodeRadius + NODE_GAP;

  // Two nodes on the same point: no direction exists, so none is claimed.
  if (length <= 2 * trim) {
    return {
      id: link.id,
      sourceNodeId: link.sourceNodeId,
      targetNodeId: link.targetNodeId,
      x1: source.x,
      y1: source.y,
      x2: target.x,
      y2: target.y,
      arrow: null,
      label,
    };
  }

  const ux = dx / length;
  const uy = dy / length;
  const x1 = source.x + ux * trim;
  const y1 = source.y + uy * trim;
  const x2 = target.x - ux * trim;
  const y2 = target.y - uy * trim;

  // The head sits *on* the trimmed end and points at the target; its base is one arrow-length back
  // along the arc, offset either side by the half-width. Perpendicular is (−uy, ux).
  const baseX = x2 - ux * ARROW_LENGTH;
  const baseY = y2 - uy * ARROW_LENGTH;
  const arrow = [
    `${round2(x2)},${round2(y2)}`,
    `${round2(baseX - uy * ARROW_HALF_WIDTH)},${round2(baseY + ux * ARROW_HALF_WIDTH)}`,
    `${round2(baseX + uy * ARROW_HALF_WIDTH)},${round2(baseY - ux * ARROW_HALF_WIDTH)}`,
  ].join(' ');

  return {
    id: link.id,
    sourceNodeId: link.sourceNodeId,
    targetNodeId: link.targetNodeId,
    x1: round2(x1),
    y1: round2(y1),
    x2: round2(x2),
    y2: round2(y2),
    arrow,
    label,
  };
}

/**
 * A coordinate as it is written into the DOM.
 *
 * Two decimals, for the reason `sparkline-geometry.coordinate` gives: it is well under a device pixel
 * on a box a few hundred units wide, and it keeps `13.000000000000002` out of both the attribute and
 * the spec.
 */
function round2(value: number): number {
  return Number.isFinite(value) ? Number(value.toFixed(2)) : 0;
}
