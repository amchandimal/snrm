import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { ElementSeriesIndex } from '../../../core/element-series';
import { Id, NetworkLink, NetworkNode } from '../../../core/models';
import { NODE_TYPE_PROFILES } from '../../network-editor/echelon-rules';
import {
  MiniMapBox,
  MiniMapLink,
  MiniMapNode,
  MiniMapSelection,
  WHOLE_NETWORK,
  miniMap,
} from '../mini-map-layout';
import { cursorTints, fillScales } from '../period-cursor';

/** One node of the miniature with its tint at the cursor already resolved into what SVG needs. */
interface PaintedNode {
  readonly node: MiniMapNode;
  /** True when this run has a series for the node - an untinted dot is not a tint reading empty. */
  readonly tinted: boolean;
  /** Top edge of the filled band, in user units. Equal to the dot's bottom when it holds nothing. */
  readonly gaugeTop: number;
  /** The element's own opacity: 1 fully available, 0.65 fully dark. */
  readonly paint: number;
  /** The availability halo's opacity, 0 for a fully available node - then it is not drawn at all. */
  readonly halo: number;
  /** This node's gauge clip, unique to this component instance. */
  readonly clipId: string;
}

/** One arc with its tint. An arc holds no stock, so availability is the whole of its tint. */
interface PaintedLink {
  readonly link: MiniMapLink;
  readonly paint: number;
  readonly halo: number;
}

/**
 * Distinguishes this instance's gauge clip ids from any other's.
 *
 * An SVG `id` is document-global, so two miniatures on one page would otherwise clip each other's
 * dots. That was a precaution when the dashboard was the only caller and is load-bearing now: the
 * side-by-side window of FR-25 puts up to twelve of these on one page by design.
 */
let inspectorInstances = 0;

/**
 * How many nodes a miniature will label before the names start colliding.
 *
 * A four-echelon sample reads far better with `SUP-1` beside its dot than with a tooltip; a
 * hundred-node network reads as a wall of overlapping text. Above the limit the names live on hover
 * and in the scope line, which is where a reader who has clicked something looks anyway.
 */
const LABEL_LIMIT = 14;

/**
 * A read-only miniature of a network - hand-drawn SVG (FR-22, FR-25).
 *
 * > "a read-only miniature of the run's network, hand-drawn SVG under the same rule as every chart
 * > in the application - Cytoscape stays out of the dashboard bundle, and a miniature needs a click
 * > target per element, not an editing surface."
 *
 * ## Hand-drawn, and why
 *
 * Cytoscape is ~400 KB of graph engine for pan, zoom, layout, edge handles and gesture routing -
 * every one of which this surface deliberately does not have. What it needs is a dot per node, a line
 * per arc and a click target on each, which is `mini-map-layout.ts` and forty lines of template. The
 * charting rule taken one shape further: reach for the library when a picture needs
 * interaction, not when it needs a shape. FR-25 repeats the rule for its own window in as many words
 * - "Cytoscape stays out of this bundle for the reason it stays out of the dashboard's" - which is
 * the second reason this component is reused rather than reimplemented: a copy would be a second
 * place for that decision to be revisited.
 *
 * ## It draws *a* network, and the caller says which (FR-25)
 *
 * It used to read `RunResultsStore` directly, on the argument that it was the results page's own
 * part. FR-25 made that false: the side-by-side window draws one of these per pane, for networks
 * that have no run at all and may never have had one. So the generalisation was made **in place**
 * rather than by copying the drawing - the network, the selection and the tints are inputs now, and
 * `results-dashboard.component.html` passes exactly the signals this component used to reach in and
 * take. Nothing about what the dashboard renders changed; what changed is who says what to draw.
 *
 * Every input past the two required ones defaults to what the dashboard was doing before them, which
 * is this repository's rule for growing a shared component (`shared/confirm-dialog`'s
 * `<ng-content />`, `shared/file-drop`'s `prompt`/`hint`): a caller that supplies nothing renders
 * what was there. Here the *new* caller is the one that supplies the negatives - no legend, no scope
 * line, no tints - because a pane one sixth of a window across has room for the picture and the
 * window says the rest once.
 *
 * ### Why the tint inputs are flat rather than one object
 *
 * Because {@link scales} must be computed **once per run** and {@link tints} once per period, which
 * is `playback-channels.indexElements`' split and the thing that keeps a cursor step from walking
 * every node's whole horizon. An input object rebuilt in the caller's computed would hand this
 * component a new identity on every step and re-run `fillScales` with it. Two signals that change on
 * two schedules stay two inputs.
 *
 * ## Nothing here writes to the network
 *
 * A node is not draggable, there is no `PATCH /networks/{id}/nodes/positions` behind this component,
 * and the fallback arrangement is never persisted. On the dashboard the network is frozen
 * while a locking run exists; in the side-by-side window the view is read-only by
 * definition - "nothing here edits, runs or deletes" (FR-25). An editable miniature would be offering
 * a gesture neither surface has anywhere to send.
 *
 * ## It tints at a period cursor, and there is no clock behind it (FR-22)
 *
 * > "the miniature tints availability and fill at that period so a disruption's footprint is visible
 * > while it is scrubbed."
 *
 * Two channels, and only two: a node's **fill** - its own on-hand against its own
 * horizon maximum - and every element's **availability**, as the red halo the canvas draws it as
 * (FR-16's channel, made temporal by FR-18). Both numbers are `playback-channels`' own, through
 * `period-cursor.cursorTints`, so the dot that is half full here is the node that is half full on the
 * canvas at the same period of the same run. No third channel was invented: flow has the arc width on
 * the canvas and a chart of its own on the dashboard, and a third meaning on an 8-unit dot would make
 * all three unreadable - which is the rule the canvas states for its own channels.
 *
 * **No loop, no timer, no `requestAnimationFrame`.** A step of the cursor recomputes
 * {@link paintedNodes} and {@link paintedLinks}, and Angular writes the handful of attributes that
 * actually differ. Two things keep that cheap and are the template's half of the canvas effect's
 * compare-before-write discipline: {@link map} is a computed of its own, so the layout - every
 * coordinate, every arrow polygon - is *not* rebuilt when the period changes, and the tints are
 * rounded in `period-cursor.ts` to what a screen can draw, so a difference below a pixel produces the
 * same attribute value and no write at all.
 *
 * A caller with no element series - every side-by-side pane, and the dashboard before the first
 * gesture that asks for one - passes `elements: null` and gets the plain dots. That is one state, not
 * a degraded one: `cursorTints` answers `NO_TINTS`, and an element with no entry keeps its ordinary
 * drawing rather than one reading empty and fully available (`applyPlayback`'s rule). {@link note} is
 * where a caller that has something to say about *why* says it.
 */
@Component({
  selector: 'app-network-inspector',
  standalone: true,
  templateUrl: './network-inspector.component.html',
  styleUrl: './network-inspector.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NetworkInspectorComponent {
  /** Prefix for this instance's gauge clip ids - see {@link inspectorInstances}. */
  private readonly clipPrefix = `snrm-mini-${++inspectorInstances}`;

  // ------------------------------------------------------------------- what to draw

  readonly nodes = input.required<readonly NetworkNode[]>();
  readonly links = input.required<readonly NetworkLink[]>();

  /** Why there is no picture, or null. Shown in place of the drawing, as the only thing in it. */
  readonly structureError = input<string | null>(null);

  /**
   * The network's structure is still being read.
   *
   * Only ever shown while there is nothing to draw - a caller that has an old picture and a request
   * in flight keeps the picture, which is what stops a refresh flashing a blank panel.
   */
  readonly loading = input(false);

  /** What the "still reading" line says. Defaults to the dashboard's own words. */
  readonly loadingLabel = input('Reading this run’s network…');

  // ------------------------------------------------------------------- the selection

  /** Which element is lit. The caller owns it: one page, one selection. */
  readonly selection = input<MiniMapSelection>(WHOLE_NETWORK);

  readonly nodePicked = output<MiniMapNode>();
  readonly linkPicked = output<MiniMapLink>();
  /** Empty space - the whole network on the dashboard, and clearing the shared key in a pane. */
  readonly networkPicked = output<void>();

  // ------------------------------------------------------------------- the tints (FR-22)

  /**
   * The run's element series, or null for a miniature with no run behind it.
   *
   * Its own input rather than part of a tint object, and not for tidiness: {@link scales} walks every
   * node's whole horizon and must re-run only when *this* changes. See the class note.
   */
  readonly elements = input<ElementSeriesIndex | null>(null);

  /** Where the shared period cursor stands. Ignored entirely when {@link elements} is null. */
  readonly cursorPeriod = input(0);

  /**
   * One line under the legend: what the tint is, or why there is none.
   *
   * An input rather than a computation, because every version of that sentence is a statement about
   * a *run* - "this run recorded no element detail", "move the cursor to tint this map" - and the
   * caller that has the run is the one that can make it. A miniature with no run behind it passes
   * nothing and the line is not drawn.
   */
  readonly note = input<string | null>(null);

  // ------------------------------------------------------------------- chrome

  /** The four-type legend under the picture. Off in a pane, where the window states it once. */
  readonly showLegend = input(true);

  /**
   * The "Scoped to DC-1 - show the whole network" line.
   *
   * Off in a side-by-side pane, which says something different about the same selection: whether
   * *this* network has the element at all (FR-25), which is a sentence only the pane can write.
   */
  readonly showSelectionLine = input(true);

  // ------------------------------------------------------------------- the drawing

  /**
   * The drawing box, in the SVG's own user units.
   *
   * A fixed viewBox scaled by CSS, for the reason `performance-curve` gives. The node radius is part
   * of the box because the fit has to keep a dot at the edge of the bounding box inside the panel -
   * a layout fitted to the centres alone clips half of whichever node is furthest left.
   */
  readonly box: MiniMapBox = { width: 340, height: 240, pad: 10, nodeRadius: 8 };

  readonly viewBox = `0 0 ${this.box.width} ${this.box.height}`;

  /** The whole picture, recomputed only when the structure changes - never per selection. */
  readonly map = computed(() => miniMap(this.nodes(), this.links(), this.box));

  readonly empty = computed(() => this.map().empty);

  /** True while the caller is reading a network and there is nothing yet to draw. */
  readonly stillReading = computed(
    () => this.loading() && this.map().empty && this.structureError() === null,
  );

  readonly showLabels = computed(() => this.map().nodes.length <= LABEL_LIMIT);

  /** The four types, for the legend - the palette itself, so it cannot drift from the dots. */
  readonly legend = NODE_TYPE_PROFILES;

  /**
   * What the picture is, when it is not the researcher's own layout.
   *
   * Said on screen because a reader who arranged this network on the canvas would otherwise take an
   * echelon layout for a canvas they do not remember arranging. It matters more in a side-by-side
   * pane than anywhere: two variants drawn by two different arrangements are two pictures that
   * cannot be compared by eye, and the reader has to be told which one they are looking at.
   */
  readonly arrangementNote = computed<string | null>(() =>
    this.map().arrangement === 'echelon'
      ? 'Some nodes carry no canvas coordinates, so this is laid out by echelon - supplier to '
        + 'customer, left to right. It is computed here and never saved.'
      : null,
  );

  // -------------------------------------------------------------- the cursor's tints (FR-22)

  /**
   * Each node's normalising maximum, taken **once per run** and not per period.
   *
   * `playback-channels.indexElements`' own split, and its reason: a maximum is a property of the
   * whole horizon and cannot change while a completed run is on screen, so recomputing it per step
   * would be a pass over every series to arrive at the same number.
   */
  private readonly scales = computed(() => fillScales(this.elements()));

  /** Every element's fill and lost availability at the cursor - one lookup per element. */
  private readonly tints = computed(() =>
    cursorTints(this.elements(), this.scales(), this.cursorPeriod()),
  );

  /**
   * The nodes as they are drawn at the cursor: the layout, plus the two attributes the tint moves.
   *
   * The layout itself comes from {@link map} and is untouched here - a step of the cursor rebuilds
   * this small array and leaves every coordinate and every arrow polygon exactly as it was.
   */
  readonly paintedNodes = computed<readonly PaintedNode[]>(() => {
    const tints = this.tints().nodes;
    const radius = this.box.nodeRadius;
    return this.map().nodes.map((node) => {
      const tint = tints.get(node.id);
      return {
        node,
        tinted: tint !== undefined,
        // The filled band's top edge: an empty node's is the bottom of its dot, a full one's is the
        // top. The clip rect runs from here downwards, so only this number moves as stock changes.
        gaugeTop: round2(node.y + radius - 2 * radius * (tint?.fill ?? 0)),
        paint: round2(1 - (tint?.dim ?? 0)),
        halo: tint?.dim ?? 0,
        clipId: `${this.clipPrefix}-gauge-${node.id}`,
      };
    });
  });

  readonly paintedLinks = computed<readonly PaintedLink[]>(() => {
    const tints = this.tints().links;
    return this.map().links.map((link) => {
      const tint = tints.get(link.id);
      return { link, paint: round2(1 - (tint?.dim ?? 0)), halo: tint?.dim ?? 0 };
    });
  });

  // ------------------------------------------------------------------------ the selection

  /** `DC-1` / `DC-1 → CUST-1` / null - what is lit, named. */
  readonly selectionLabel = computed<string | null>(() => {
    const selection = this.selection();
    if (selection.kind === 'node') {
      return this.map().nodes.find((node) => node.id === selection.id)?.name ?? `#${selection.id}`;
    }
    if (selection.kind === 'link') {
      return this.map().links.find((link) => link.id === selection.id)?.label ?? `#${selection.id}`;
    }
    return null;
  });

  isNodeSelected(id: Id): boolean {
    const selection = this.selection();
    return selection.kind === 'node' && selection.id === id;
  }

  isLinkSelected(id: Id): boolean {
    const selection = this.selection();
    return selection.kind === 'link' && selection.id === id;
  }

  selectNode(node: MiniMapNode): void {
    this.nodePicked.emit(node);
  }

  selectLink(link: MiniMapLink): void {
    this.linkPicked.emit(link);
  }

  /** The empty-space click of FR-22 - and of FR-25, where it drops the shared by-name key. */
  selectNetwork(): void {
    this.networkPicked.emit();
  }

  title(node: MiniMapNode): string {
    return `${node.name} - ${node.type}`;
  }
}

/**
 * A coordinate or an opacity as it is written into the DOM.
 *
 * Two decimals, for the reason `mini-map-layout.round2` and `sparkline-geometry.coordinate` give: it
 * is well under a device pixel here, and - since these are recomputed on every step of the cursor -
 * it is also what stops a value differing in its sixteenth decimal from writing an attribute and so
 * repainting an element with nothing new to say (`graph-canvas.writeData`'s discipline, in a
 * template).
 */
function round2(value: number): number {
  return Number.isFinite(value) ? Number(value.toFixed(2)) : 0;
}
