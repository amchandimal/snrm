import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  effect,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import cytoscape from 'cytoscape';
import dagre from 'cytoscape-dagre';
import edgehandles from 'cytoscape-edgehandles';

import { Id, NetworkLink, NetworkNode, NodeType } from '../../../core/models';
import { formatDuration, formatRate } from '../../../core/time-units';
import { DisruptionOverlay } from '../disruption-overlay';
import { DisruptionsStore } from '../disruptions.store';
import { ECHELON_RANK, nodeTypeProfile } from '../echelon-rules';
import { NodeMove } from '../editor-commands';
import {
  CAPTION_CLASS,
  CAPTION_FONT_SIZE,
  CaptionElement,
  LABEL_PADDING,
  captionElementId,
  captionElements,
  captionOwnerElementId,
  isCaptionElementId,
  linkCaptionAnchor,
  nodeCaptionAnchor,
} from '../element-captions';
import { NetworkEditorStore } from '../network-editor.store';
import { NodePaletteComponent } from '../node-palette/node-palette.component';
import {
  IDLE_LINK_OPACITY,
  PlaybackElements,
  fillLevel,
  flowWidth,
  gaugeColours,
  gaugeStops,
  unavailableOpacity,
  valueAt,
} from '../playback-channels';
import { PlaybackStore } from '../playback.store';
import { TopologicalMetricsStore } from '../topological-metrics.store';

/** A hover card over an element: where it sits, and the lines it shows. */
interface CanvasTooltip {
  readonly x: number;
  readonly y: number;
  readonly title: string;
  readonly lines: readonly string[];
  /**
   * The windows and severities striking this element, one line each (FR-16).
   *
   * > "A targeted element carries a disruption badge, with its window and severity on hover."
   *
   * Kept apart from {@link lines} rather than appended to them: those describe the element, these
   * describe something being done to it, and running the two together would read as more attributes.
   */
  readonly disruptions: readonly string[];
}

/**
 * Extensions register on the `cytoscape` function itself, so exactly once per page.
 *
 * The editor route is lazy-loaded and can be entered, left and re-entered; registering on every
 * construction makes Cytoscape warn and, for edgehandles, install a second set of listeners.
 */
let extensionsRegistered = false;

function registerExtensions(): void {
  if (extensionsRegistered) {
    return;
  }
  cytoscape.use(dagre);
  cytoscape.use(edgehandles);
  extensionsRegistered = true;
}

/** Prefixes keep node and edge element ids in separate namespaces - both come from `bigint`. */
const NODE_ELEMENT_PREFIX = 'n';
const EDGE_ELEMENT_PREFIX = 'l';

/**
 * The drag handle, which this component has to supply itself.
 *
 * `cytoscape-edgehandles` v4 dropped the hover handle that v3 had: its only built-in trigger is a
 * `tapstart` on a node while *draw mode* is on, and draw mode calls `cy.autoungrabify(true)`, so it
 * trades away node dragging wholesale. Both gestures have to work at once, so the handle is a real
 * Cytoscape node here - real, rather than an HTML overlay, because `eh.start()` only produces a
 * rubber band if Cytoscape itself is tracking the drag, and it only tracks drags that began with a
 * `tapstart` inside the canvas.
 *
 * The id must not parse as a node or a link id; `render()` keys off exactly that to leave it alone.
 */
const HANDLE_ID_PREFIX = 'snrm-eh-handle';
const HANDLE_CLASS = 'snrm-eh-handle';

/** Diameter of one corner handle, in model pixels. Matches the `.snrm-eh-handle` style rule. */
const HANDLE_SIZE = 11;

/**
 * One handle on each corner of the node's bounding box.
 *
 * Corners rather than a single handle so the gesture can be started from whichever side the target
 * lies on, without dragging back across the node first. Each entry is a unit offset from the node's
 * centre, scaled by half its width and height.
 */
const HANDLE_CORNERS: readonly { readonly id: string; readonly x: number; readonly y: number }[] = [
  { id: `${HANDLE_ID_PREFIX}-nw`, x: -1, y: -1 },
  { id: `${HANDLE_ID_PREFIX}-ne`, x: 1, y: -1 },
  { id: `${HANDLE_ID_PREFIX}-se`, x: 1, y: 1 },
  { id: `${HANDLE_ID_PREFIX}-sw`, x: -1, y: 1 },
];

/**
 * Grace period when the pointer crosses the gap between a node and one of its handles.
 *
 * Generous on purpose: on a round or diamond node the bounding-box corner sits well clear of the
 * drawn edge, so there is real empty space to cross where neither element is hovered.
 */
const HANDLE_HIDE_DELAY_MS = 160;

/** Milliseconds and pixels within which two background taps count as a double-click. */
const DOUBLE_TAP_MS = 350;
const DOUBLE_TAP_SLOP = 12;

/** Spacing of the fallback grid a node without coordinates is parked on until it is placed. */
const FALLBACK_COLUMN = 260;
const FALLBACK_ROW = 96;

/**
 * The Cytoscape.js canvas.
 *
 * ## One-way rendering
 *
 * `NetworkEditorStore` is authoritative and this component renders it: one effect diffs the store's
 * node and link maps against `cy.elements()` inside a `cy.batch()`. Nothing here writes to the store
 * except by raising a command. `#syncing` suppresses the gesture handlers while the effect runs, so
 * a programmatic reposition cannot echo back as a user move.
 *
 * Pan, zoom and the rubber-band preview never leave Cytoscape. They are view state with no bearing
 * on the model, and pushing a wheel event through Angular's change detection is how a graph canvas
 * starts to feel slow.
 *
 * ## One effect per concern
 *
 * Seven constructor effects, each reading the signals of one concern and writing through one
 * `apply*` method inside a `cy.batch()`: the structure, the **captions** (FR-30), the criticality
 * sizing, the disruption overlay (FR-16), **visual playback** (FR-18), the selection and
 * the interaction modes. They are separate because their inputs change on entirely different
 * occasions - a metric suite arrives long after the nodes do, a scenario is switched without the
 * topology moving, and playback repaints every period of a running clock. Re-diffing every element
 * and every arc to resize six nodes, or to light six halos, would be work for nothing.
 *
 * ## Three kinds of element live on this canvas, and only one of them is the network
 *
 * The nodes and arcs of the store, drawn with ids that parse (`n12`, `l30`); the four corner drag
 * handles and edgehandles' own ghost and preview, whose ids do not; and, since FR-30, one
 * **caption element** per captioned node and arc. Every pass over the canvas states which of the
 * three it is addressing, and removes only what it recognises - `render` removes only parseable
 * ids, `syncCaptions` removes only caption ids. That is what makes a render firing mid-gesture
 * harmless: it cannot take the rubber band, and it cannot take a caption either.
 */
@Component({
  selector: 'app-graph-canvas',
  standalone: true,
  template: `
    <div
      #host
      class="graph-canvas"
      [class.graph-canvas--drop-target]="dropActive()"
      [class.graph-canvas--readonly]="store.readOnly()"
      (dragover)="onDragOver($event)"
      (dragleave)="onDragLeave($event)"
      (drop)="onDrop($event)"
    >
      <!-- Cytoscape draws into a canvas element, so a node has no DOM node to hang a title on;
           the tooltip is this overlay, positioned from the hovered node's rendered
           coordinates. -->
      @if (tooltip(); as hovered) {
        <div class="graph-canvas__tooltip" [style.left.px]="hovered.x" [style.top.px]="hovered.y">
          <strong>{{ hovered.title }}</strong>
          @for (line of hovered.lines; track line) {
            <span>{{ line }}</span>
          }
          @if (hovered.disruptions.length) {
            <span class="graph-canvas__tooltip-rule" aria-hidden="true"></span>
            @for (line of hovered.disruptions; track $index) {
              <span class="graph-canvas__tooltip-disruption">⚡ {{ line }}</span>
            }
          }
        </div>
      }
    </div>
  `,
  styleUrl: './graph-canvas.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphCanvasComponent implements AfterViewInit {
  readonly store = inject(NetworkEditorStore);
  private readonly metrics = inject(TopologicalMetricsStore);
  private readonly disruptions = inject(DisruptionsStore);
  private readonly playback = inject(PlaybackStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');

  /** Raised when a gesture creates something, so the editor can focus the property panel. */
  readonly elementCreated = output<void>();
  /** Raised when a gesture is refused on a frozen network, so the fork prompt can open. */
  readonly blockedByReadOnly = output<void>();

  /** True while a palette drag is over the canvas - drives the drop-target outline. */
  readonly dropActive = signal(false);

  /** The hovered element's tooltip, or null. Declared units only - never periods. */
  readonly tooltip = signal<CanvasTooltip | null>(null);

  private cy: cytoscape.Core | null = null;
  private eh: cytoscape.EdgeHandlesInstance | null = null;
  private readonly ready = signal(false);

  /** Suppresses gesture handlers while the render effect is writing to Cytoscape. */
  private syncing = false;

  /**
   * Whether the canvas currently carries playback styling.
   *
   * The flag is what makes leaving playback a single teardown rather than something every repaint
   * has to consider: with it false there is nothing on the elements to remove, so the effect firing
   * on a canvas that has never played costs one comparison.
   */
  private playbackPainted = false;

  /**
   * How many captions this canvas is currently drawing (FR-30).
   *
   * A counter rather than a `cy.$('.snrm-caption')` query because the `position` handler reads it,
   * and that fires per node per frame: a selector query there would make a drag of *n* nodes cost a
   * pass over every element on the canvas *n* times a frame. Zero is the ordinary state of a
   * network nobody has annotated, and it is what makes this feature free on one.
   */
  private captionsWanted = 0;

  /** Node positions captured at `grab`, so `free` can report a before and an after. */
  private dragStart: Map<Id, { posX: number; posY: number }> | null = null;
  private dragSettleTimer: ReturnType<typeof setTimeout> | null = null;

  /** Where a node with null coordinates is parked. Assigned once so it does not wander. */
  private readonly fallbackPositions = new Map<Id, cytoscape.Position>();
  private fallbackSeq = 0;

  /** Source node of the edge-handle gesture in progress, or null. */
  private drawSource: cytoscape.NodeSingular | null = null;

  /** The node the hover handle is currently attached to, or null when it is hidden. */
  private handleFor: cytoscape.NodeSingular | null = null;
  private handleHideTimer: ReturnType<typeof setTimeout> | null = null;

  /** Set while {@link cancelDraw} tears a gesture down, so `ehcomplete` knows not to create a link. */
  private cancellingDraw = false;

  private lastBackgroundTap = { at: 0, x: 0, y: 0 };

  constructor() {
    effect(() => {
      const nodes = this.store.nodes();
      const links = this.store.links();
      const warnings = this.store.linkWarnings();
      if (!this.ready()) {
        return;
      }
      this.render(nodes, links, warnings);
    });

    // The captions of FR-30. Registered **immediately after `render`** and reading the same two
    // signals, so on any change to the network the two run back to back in one flush: the elements
    // are on the canvas, at their sizes, before a caption is anchored beneath one. Its own effect
    // rather than a call at the end of `render` because a caption edit is far more common than a
    // structural one and this pass is the cheaper of the two - and because a caption re-stamped
    // from inside `render` as well as from here would be stamped twice on every edit.
    effect(() => {
      const nodes = this.store.nodes();
      const links = this.store.links();
      if (!this.ready()) {
        return;
      }
      this.syncCaptions(nodes, links);
    });

    // Node size encodes NODE_CRITICALITY when the toggle is on. A separate effect from
    // `render` on purpose: criticality arrives from the server long after the nodes do, and it
    // changes without the topology changing at all - re-diffing every element and every arc to
    // resize six nodes would be work for nothing.
    effect(() => {
      const criticality = this.metrics.criticality();
      const encode = this.metrics.sizeByCriticality();
      if (!this.ready()) {
        return;
      }
      this.applyCriticality(criticality, encode);
    });

    // The disruption overlay / FR-16. Its own effect for the same reason criticality has
    // one: a scenario arrives, changes and is switched independently of the network, and re-diffing
    // every element to light six halos would be work for nothing.
    effect(() => {
      const overlay = this.disruptions.overlay();
      const draftRegion = this.disruptions.draftRegionNodes();
      if (!this.ready()) {
        return;
      }
      this.applyDisruptions(overlay, draftRegion);
    });

    // Visual playback (FR-18). One effect for the whole animation, and the only one that
    // repaints on the clock: it reads `currentPeriod`, which the playback store writes at most once
    // per *period* - never per frame - so the canvas repaints when the picture changes and not
    // sixty times a second to redraw the same one.
    effect(() => {
      const period = this.playback.currentPeriod();
      const elements = this.playback.elements();
      const enabled = this.playback.enabled();
      if (!this.ready()) {
        return;
      }
      this.applyPlayback(period, elements, enabled);
    });

    effect(() => {
      const nodeIds = this.store.selectedNodeIds();
      const linkIds = this.store.selectedLinkIds();
      if (!this.ready()) {
        return;
      }
      this.applySelection(nodeIds, linkIds);
    });

    effect(() => {
      const readOnly = this.store.readOnly();
      const boxSelect = this.store.boxSelect();
      if (!this.ready()) {
        return;
      }
      this.applyModes(readOnly, boxSelect);
    });
  }

  ngAfterViewInit(): void {
    registerExtensions();
    const container = this.host().nativeElement;

    this.cy = cytoscape({
      container,
      style: canvasStyle(),
      // Selection is a first-class gesture here, so box selection is always armed; the
      // toolbar toggle decides whether a plain background drag pans or rubber-bands.
      boxSelectionEnabled: true,
      selectionType: 'single',
      wheelSensitivity: 0.2,
      minZoom: 0.15,
      maxZoom: 3,
    });

    this.wireGestures(this.cy);
    this.wireEdgeHandles(this.cy);

    const observer = new ResizeObserver(() => this.cy?.resize());
    observer.observe(container);

    this.destroyRef.onDestroy(() => {
      observer.disconnect();
      if (this.dragSettleTimer !== null) {
        clearTimeout(this.dragSettleTimer);
      }
      this.cancelHandleHide();
      this.eh?.destroy();
      this.cy?.destroy();
      this.cy = null;
      this.eh = null;
    });

    this.ready.set(true);
  }

  // ------------------------------------------------------------------- public surface

  /** Frame the whole network. Called from the toolbar and after an auto-layout. */
  fit(): void {
    this.cy?.fit(undefined, 60);
  }

  /**
   * Abandon an edge-drawing gesture in progress. Bound to Escape.
   *
   * `eh.stop()` alone is not a cancel: it runs the normal end-of-gesture path, which *creates* the
   * link if a target happens to be latched - with `snap: true` one usually is. The flag is what
   * makes it a cancel; `ehcomplete` still fires, and reads it as "tear down, do not connect".
   *
   * @returns true if a gesture was cancelled, so the caller knows whether Escape was consumed
   */
  cancelDraw(): boolean {
    if (!this.drawSource || !this.eh) {
      return false;
    }
    this.cancellingDraw = true;
    this.eh.stop();
    return true;
  }

  /**
   * `cytoscape-dagre`, then a hard snap of x to the echelon column.
   *
   * > "auto-layout button (layered left-to-right by echelon, via `cytoscape-dagre`)"
   *
   * dagre on its own lays out by graph depth, which coincides with echelon only in a network where
   * every path is a clean SUPPLIER→PLANT→DC→CUSTOMER chain - precisely not the networks this editor
   * exists to build. So dagre is left to do what it is good at, ordering nodes within a layer to
   * minimise crossings (its **y**), and the echelon is imposed on the **x** afterwards.
   *
   * The result is handed to the store as a single undoable move, and reaches the server as one bulk
   * position PATCH.
   */
  async runAutoLayout(): Promise<void> {
    const cy = this.cy;
    if (!cy || cy.nodes().length === 0) {
      return;
    }
    const options: cytoscape.DagreLayoutOptions = {
      name: 'dagre',
      rankDir: 'LR',
      nodeSep: 40,
      rankSep: 120,
      edgeSep: 16,
      ranker: 'network-simplex',
      fit: false,
      animate: false,
    };
    // **The layout runs over the network and nothing else.** `cy.layout()` would take every element
    // on the canvas, and since FR-30 that includes a caption element per captioned node and arc -
    // unconnected point nodes, which dagre would lay out as their own components and make room for,
    // distorting the arrangement of the real network to accommodate labels that only exist to sit
    // underneath it. The drag handles are excluded on the same terms; they happen not to be on the
    // canvas when the toolbar button is pressed, which is luck rather than a rule.
    //
    // `LayoutOptions` is a closed union of the built-in layouts, so an extension's options never
    // belong to it. The cast is the seam where the extension meets the core types.
    const layout = cy
      .elements()
      .filter((element) => !element.hasClass(CAPTION_CLASS) && !element.hasClass(HANDLE_CLASS))
      .layout(options as unknown as cytoscape.LayoutOptions);

    await new Promise<void>((resolve) => {
      layout.one('layoutstop', () => resolve());
      layout.run();
    });

    // Only real nodes. The drag handle is a Cytoscape node too, and letting it set the left edge of
    // the supplier column would shift the whole layout by wherever the pointer last rested.
    const laidOut: { id: Id; type: NodeType; x: number; y: number }[] = [];
    cy.nodes().forEach((element) => {
      const id = nodeIdOf(element.id());
      if (id === null) {
        return;
      }
      laidOut.push({
        id,
        type: element.data('type') as NodeType,
        x: element.position('x'),
        y: element.position('y'),
      });
    });
    if (!laidOut.length) {
      return;
    }

    const columnOrigin = Math.min(...laidOut.map((node) => node.x));
    const positions = new Map<Id, { posX: number; posY: number }>();
    for (const node of laidOut) {
      positions.set(node.id, {
        posX: this.store.snap(columnOrigin + ECHELON_RANK[node.type] * FALLBACK_COLUMN),
        posY: this.store.snap(node.y),
      });
    }

    await this.store.applyLayout(positions);
    this.fit();
  }

  // ------------------------------------------------------------- HTML5 drag and drop

  onDragOver(event: DragEvent): void {
    const transfer = event.dataTransfer;
    if (!transfer?.types.includes(NodePaletteComponent.DRAG_TYPE)) {
      return;
    }
    // Without preventDefault the browser refuses the drop outright.
    event.preventDefault();
    transfer.dropEffect = 'copy';
    this.dropActive.set(true);
  }

  onDragLeave(event: DragEvent): void {
    // `dragleave` fires for every child too, and Cytoscape fills the host with its own canvases.
    // Only clear the outline when the pointer has left the host entirely.
    const related = event.relatedTarget;
    if (!(related instanceof Node) || !this.host().nativeElement.contains(related)) {
      this.dropActive.set(false);
    }
  }

  async onDrop(event: DragEvent): Promise<void> {
    const type = event.dataTransfer?.getData(NodePaletteComponent.DRAG_TYPE) as NodeType | undefined;
    this.dropActive.set(false);
    if (!type) {
      return;
    }
    event.preventDefault();
    if (this.store.readOnly()) {
      this.blockedByReadOnly.emit();
      return;
    }
    const at = this.toModelPosition(event.clientX, event.clientY);
    const created = await this.store.createNodeAt(type, at.x, at.y);
    if (created) {
      this.elementCreated.emit();
    }
  }

  /** Screen coordinates to Cytoscape model coordinates, through the current pan and zoom. */
  private toModelPosition(clientX: number, clientY: number): cytoscape.Position {
    const cy = this.cy;
    const rect = this.host().nativeElement.getBoundingClientRect();
    if (!cy) {
      return { x: clientX - rect.left, y: clientY - rect.top };
    }
    const pan = cy.pan();
    const zoom = cy.zoom();
    return {
      x: (clientX - rect.left - pan.x) / zoom,
      y: (clientY - rect.top - pan.y) / zoom,
    };
  }

  // -------------------------------------------------------------------- cy → commands

  private wireGestures(cy: cytoscape.Core): void {
    cy.on('select unselect', () => {
      if (this.syncing) {
        return;
      }
      this.pushSelectionToStore(cy);
    });

    // The tooltip is anchored to rendered coordinates, so anything that moves the viewport retires
    // it rather than letting it drift away from its node.
    cy.on('pan zoom', () => this.tooltip.set(null));

    // A caption follows the thing it describes, at frame rate (FR-30).
    //
    // The store only hears about a drag when it ends - one `MoveNodesCommand` for the whole moved
    // selection - so a caption that waited for `render` would sit where the node used to be for the
    // length of the gesture. `position` is the one event that fires for *every* way a node moves:
    // a user drag, the auto-layout writing coordinates back, and `render` correcting a position
    // after a save. An arc's caption moves with either endpoint, so a node's incident edges are
    // re-anchored with it.
    cy.on('position', 'node', (event) => {
      const node = event.target as unknown as cytoscape.NodeSingular;
      // Our own writes below move caption elements, which fire `position` in turn. Nothing follows a
      // caption, so returning here is what keeps that from being a loop.
      if (this.captionsWanted === 0 || isCaptionElementId(node.id())) {
        return;
      }
      this.followCaptions(node);
    });

    cy.on('grab', 'node', () => {
      if (this.syncing) {
        return;
      }
      // A node being dragged must not leave its handle floating beside where it used to be.
      this.removeHandle();
      this.tooltip.set(null);
      const start = new Map<Id, { posX: number; posY: number }>();
      cy.nodes().forEach((element) => {
        const id = nodeIdOf(element.id());
        if (id !== null) {
          start.set(id, { posX: element.position('x'), posY: element.position('y') });
        }
      });
      this.dragStart = start;
    });

    // A multi-node drag fires `free` once per node. Settling on a timeout collapses them into one
    // undo entry - "Move 4 nodes", not four separate steps to walk back.
    cy.on('free', 'node', () => {
      if (this.syncing || !this.dragStart) {
        return;
      }
      if (this.dragSettleTimer !== null) {
        clearTimeout(this.dragSettleTimer);
      }
      this.dragSettleTimer = setTimeout(() => {
        this.dragSettleTimer = null;
        void this.commitDrag(cy);
      }, 0);
    });

    // Arcs get a hover card only once something disrupts them - see `showTooltipForEdge`.
    cy.on('mouseover', 'edge', (event) => {
      this.showTooltipForEdge(event.target as unknown as cytoscape.EdgeSingular);
    });

    cy.on('mouseout', 'edge', () => this.tooltip.set(null));

    cy.on('tap', (event) => {
      // A tap whose target is the core itself landed on empty canvas, not on an element.
      if ((event.target as unknown) !== (cy as unknown)) {
        return;
      }
      const position = event.position;
      const now = Date.now();
      const previous = this.lastBackgroundTap;
      const isDouble =
        now - previous.at < DOUBLE_TAP_MS &&
        Math.abs(position.x - previous.x) < DOUBLE_TAP_SLOP &&
        Math.abs(position.y - previous.y) < DOUBLE_TAP_SLOP;
      this.lastBackgroundTap = { at: now, x: position.x, y: position.y };
      if (!isDouble) {
        return;
      }
      this.lastBackgroundTap = { at: 0, x: 0, y: 0 };
      void this.createAtPointer(position);
    });
  }

  /** Double-clicking empty canvas creates a node of the last-used type at the pointer. */
  private async createAtPointer(position: cytoscape.Position): Promise<void> {
    if (this.store.readOnly()) {
      this.blockedByReadOnly.emit();
      return;
    }
    const created = await this.store.createNodeAt(this.store.lastUsedType(), position.x, position.y);
    if (created) {
      this.elementCreated.emit();
    }
  }

  private async commitDrag(cy: cytoscape.Core): Promise<void> {
    const start = this.dragStart;
    this.dragStart = null;
    if (!start) {
      return;
    }
    const moves: NodeMove[] = [];
    cy.nodes().forEach((element) => {
      const id = nodeIdOf(element.id());
      if (id === null) {
        return;
      }
      const from = start.get(id);
      if (!from) {
        return;
      }
      const posX = this.store.snap(element.position('x'));
      const posY = this.store.snap(element.position('y'));
      if (posX === from.posX && posY === from.posY) {
        return;
      }
      moves.push({ nodeId: id, from, to: { posX, posY } });
    });
    if (!moves.length) {
      return;
    }
    if (this.store.readOnly()) {
      // Put them back where they were; the fork prompt explains why.
      this.syncing = true;
      cy.batch(() => {
        for (const move of moves) {
          const element = cy.getElementById(
            nodeElementId(move.nodeId),
          ) as unknown as cytoscape.NodeSingular;
          element.position({ x: move.from.posX, y: move.from.posY });
        }
      });
      this.syncing = false;
      this.blockedByReadOnly.emit();
      return;
    }
    await this.store.moveNodes(moves);
  }

  private pushSelectionToStore(cy: cytoscape.Core): void {
    const nodeIds: Id[] = [];
    const linkIds: Id[] = [];
    cy.$('node:selected').forEach((element) => {
      const id = nodeIdOf(element.id());
      if (id !== null) {
        nodeIds.push(id);
      }
    });
    cy.$('edge:selected').forEach((element) => {
      const id = linkIdOf(element.id());
      if (id !== null) {
        linkIds.push(id);
      }
    });
    this.store.select(nodeIds, linkIds);
  }

  // ------------------------------------------------------------------- edge handles

  private wireEdgeHandles(cy: cytoscape.Core): void {
    this.eh = cy.edgehandles({
      // The gesture-level gate. `linkVerdict` decides; edgehandles only obeys.
      canConnect: (source, target) => {
        // `source` and `target` may be empty collections here - see `parseElementId`. Both ids come
        // back null in that case and the answer is a plain false, never a throw.
        const sourceId = nodeIdOf(source?.id());
        const targetId = nodeIdOf(target?.id());
        if (sourceId === null || targetId === null) {
          return false;
        }
        return this.store.verdictFor(sourceId, targetId).ok;
      },
      edgeParams: () => ({ data: { warned: false, badge: '' } }),
      hoverDelay: 80,
      snap: true,
      snapThreshold: 24,
      snapFrequency: 15,
      noEdgeEventsInDraw: true,
      disableBrowserGestures: true,
    });

    onEdgeHandlesEvent(cy, 'ehstart', (_event, sourceNode) => {
      this.drawSource = sourceNode;
      // The handle has done its job; leaving it on the canvas would make it a snap candidate.
      this.removeHandle();
      this.tooltip.set(null);
    });

    onEdgeHandlesEvent(cy, 'ehstop ehcancel', () => {
      this.drawSource = null;
      this.cancellingDraw = false;
      cy.nodes().removeClass('link-target-valid link-target-invalid');
    });

    // Green when edgehandles has actually latched onto a target. With `snap: true` that happens
    // within `snapThreshold` of the node, before the pointer is over it, so this - not our own
    // mouseover - is what "this will connect" really means.
    onEdgeHandlesEvent(cy, 'ehhoverover', (_event, _sourceNode, targetNode) => {
      targetNode.removeClass('link-target-invalid');
      targetNode.addClass('link-target-valid');
    });

    onEdgeHandlesEvent(cy, 'ehhoverout', (_event, _sourceNode, targetNode) => {
      targetNode.removeClass('link-target-valid');
    });

    // Red is ours alone: edgehandles only ever signals *valid* targets, and `canConnect` returning
    // false means it stays silent. The refusal has to be shown, so it is derived from the same
    // verdict that refused it.
    cy.on('mouseover', 'node', (event) => {
      const node = event.target as unknown as cytoscape.NodeSingular;
      if (isHandleId(node.id())) {
        // Moving onto a handle - or between two of them - must not retract the set.
        this.cancelHandleHide();
        return;
      }
      const source = this.drawSource;
      if (!source) {
        this.showHandleFor(node);
        this.showTooltipFor(node);
        return;
      }
      const sourceId = nodeIdOf(source.id());
      const targetId = nodeIdOf(node.id());
      if (sourceId === null || targetId === null) {
        return;
      }
      const verdict = this.store.verdictFor(sourceId, targetId);
      node.removeClass('link-target-valid link-target-invalid');
      node.addClass(verdict.ok ? 'link-target-valid' : 'link-target-invalid');
    });

    cy.on('mouseout', 'node', (event) => {
      const node = event.target as unknown as cytoscape.NodeSingular;
      node.removeClass('link-target-valid link-target-invalid');
      this.scheduleHandleHide();
      if (!isHandleId(node.id())) {
        this.tooltip.set(null);
      }
    });

    // The gesture itself. This must be a Cytoscape `tapstart` so that Cytoscape begins tracking the
    // drag - `eh.update()` is driven by its `tapdrag`, and `eh.stop()` by its `tapend`.
    cy.on('tapstart', 'node', (event) => {
      const node = event.target as unknown as cytoscape.NodeSingular;
      if (!isHandleId(node.id())) {
        return;
      }
      // Any corner starts the same gesture - they all belong to whichever node is hovered.
      const source = this.handleFor;
      this.removeHandle();
      if (source && !this.store.readOnly()) {
        this.eh?.start(source);
      }
    });

    onEdgeHandlesEvent(cy, 'ehcomplete', (_event, sourceNode, targetNode, previewEles) => {
      // edgehandles promotes its preview edge into a real one rather than adding a second. Remove
      // it: the authoritative edge arrives through the store once the POST returns, and two
      // elements for one arc would confuse the diff.
      previewEles?.remove();
      if (this.cancellingDraw) {
        return;
      }
      const sourceId = nodeIdOf(sourceNode?.id());
      const targetId = nodeIdOf(targetNode?.id());
      if (sourceId === null || targetId === null) {
        return;
      }
      void this.connect(sourceId, targetId);
    });
  }

  // ------------------------------------------------------------------- the drag handle

  /** Park a handle on each corner of a hovered node's bounding box. */
  private showHandleFor(node: cytoscape.NodeSingular): void {
    const cy = this.cy;
    if (!cy || this.store.readOnly() || this.drawSource || nodeIdOf(node.id()) === null) {
      return;
    }
    this.cancelHandleHide();
    this.handleFor = node;

    const centre = node.position();
    const halfWidth = node.outerWidth() / 2;
    const halfHeight = node.outerHeight() / 2;

    cy.batch(() => {
      for (const corner of HANDLE_CORNERS) {
        const position = {
          x: centre.x + corner.x * halfWidth,
          y: centre.y + corner.y * halfHeight,
        };
        const existing = cy.getElementById(corner.id);
        if (existing.length === 0) {
          cy.add({
            group: 'nodes',
            // `size` is carried even though the `.snrm-eh-handle` rule overrides it: the base node
            // rule maps width and height from data, and a node without the key would leave that
            // mapper with nothing to read.
            data: { id: corner.id, size: HANDLE_SIZE },
            position,
            classes: HANDLE_CLASS,
            // Ungrabbable so Cytoscape does not try to drag the handle instead of letting the
            // gesture through, and unselectable so it never reaches the property panel's selection.
            grabbable: false,
            selectable: false,
          });
          continue;
        }
        (existing as unknown as cytoscape.NodeSingular).position(position);
      }
    });
  }

  private removeHandle(): void {
    this.cancelHandleHide();
    this.handleFor = null;
    this.cy?.$(`.${HANDLE_CLASS}`).remove();
  }

  // ---------------------------------------------------------------------- node tooltip

  /**
   * The hovered node's figures, in the units they were entered in.
   *
   * > "Link labels and node tooltips show declared durations in their own unit."
   *
   * Never in periods. What the clock makes of these numbers is a separate question, and the one
   * place it is answered is the resolution banner - which says so explicitly, next to the declared
   * value it is talking about.
   */
  private showTooltipFor(node: cytoscape.NodeSingular): void {
    const id = nodeIdOf(node.id());
    if (id === null) {
      return;
    }
    const model = this.store.nodes().get(id);
    if (!model) {
      return;
    }
    const box = node.renderedBoundingBox();
    this.tooltip.set({
      x: (box.x1 + box.x2) / 2,
      y: box.y1 - TOOLTIP_GAP,
      title: `${model.name} · ${model.type}`,
      lines: nodeTooltipLines(model),
      disruptions: this.disruptionLines(this.disruptions.overlay().nodes.get(id)),
    });
  }

  /**
   * The hover card for a **disrupted** arc (FR-16).
   *
   * Only for a disrupted one. An arc already carries its lead time as a label, so a tooltip on every
   * edge would be repeating what is written on it; the halo is what says there is more to read here.
   */
  private showTooltipForEdge(edge: cytoscape.EdgeSingular): void {
    const id = linkIdOf(edge.id());
    if (id === null) {
      return;
    }
    const mark = this.disruptions.overlay().links.get(id);
    const model = this.store.links().get(id);
    if (!mark || !model) {
      return;
    }
    const nodes = this.store.nodes();
    const source = nodes.get(model.sourceNodeId)?.name ?? `#${model.sourceNodeId}`;
    const target = nodes.get(model.targetNodeId)?.name ?? `#${model.targetNodeId}`;
    const box = edge.renderedBoundingBox();
    this.tooltip.set({
      x: (box.x1 + box.x2) / 2,
      y: (box.y1 + box.y2) / 2 - TOOLTIP_GAP,
      title: `${source} → ${target}`,
      lines: [
        `Lead time ${formatDuration(model.leadTime)}`,
        `Capacity ${formatRate(model.capacity)}`,
      ],
      disruptions: this.disruptionLines(mark),
    });
  }

  /** One line per event striking the element, worst-window-first as the panel lists them. */
  private disruptionLines(mark: { readonly events: readonly { readonly line: string }[] } | undefined): readonly string[] {
    return mark ? mark.events.map((entry) => entry.line) : [];
  }

  /** Crossing the seam from node to handle fires a `mouseout`; give it a moment to be a `mouseover`. */
  private scheduleHandleHide(): void {
    this.cancelHandleHide();
    this.handleHideTimer = setTimeout(() => {
      this.handleHideTimer = null;
      this.removeHandle();
    }, HANDLE_HIDE_DELAY_MS);
  }

  private cancelHandleHide(): void {
    if (this.handleHideTimer !== null) {
      clearTimeout(this.handleHideTimer);
      this.handleHideTimer = null;
    }
  }

  private async connect(sourceId: Id, targetId: Id): Promise<void> {
    if (this.store.readOnly()) {
      this.blockedByReadOnly.emit();
      return;
    }
    await this.store.connect(sourceId, targetId);
    this.elementCreated.emit();
  }

  // --------------------------------------------------------------------- store → cy

  private render(
    nodes: ReadonlyMap<Id, NetworkNode>,
    links: ReadonlyMap<Id, NetworkLink>,
    warnings: ReadonlyMap<Id, string>,
  ): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    this.syncing = true;
    cy.batch(() => {
      // Only elements whose id parses are ours to remove. An unparseable id belongs to the drag
      // handle or to edgehandles' ghost and preview elements, and a render that fires mid-gesture
      // - a selection change is enough - would otherwise delete the rubber band out from under the
      // pointer.
      //
      // Edges first on the way out: removing a node takes its edges with it, and removing an edge
      // Cytoscape has already dropped is a no-op either way.
      cy.edges().forEach((element) => {
        const id = linkIdOf(element.id());
        if (id !== null && !links.has(id)) {
          element.remove();
        }
      });
      cy.nodes().forEach((element) => {
        const id = nodeIdOf(element.id());
        if (id !== null && !nodes.has(id)) {
          element.remove();
          this.fallbackPositions.delete(id);
        }
      });

      for (const node of nodes.values()) {
        const elementId = nodeElementId(node.id);
        const existing = cy.getElementById(elementId);
        const data = nodeData(node);
        const position = this.positionFor(node);
        if (existing.length === 0) {
          cy.add({ group: 'nodes', data, position });
          continue;
        }
        const element = existing as unknown as cytoscape.NodeSingular;
        element.data(data);
        // Never fight the pointer: a node the user is holding owns its own position.
        if (!element.grabbed() && hasMoved(element.position(), position)) {
          element.position(position);
        }
      }

      for (const link of links.values()) {
        const elementId = edgeElementId(link.id);
        const existing = cy.getElementById(elementId);
        const warning = warnings.get(link.id) ?? '';
        const data: cytoscape.EdgeDataDefinition = {
          id: elementId,
          source: nodeElementId(link.sourceNodeId),
          target: nodeElementId(link.targetNodeId),
          warned: warning !== '',
          badge: warning ? '⚠' : '',
          // Link labels show the declared duration in its own unit (e.g. '6 h'), not in
          // periods, so the model reads the way the user entered it. The echelon badge shares the
          // label rather than taking a second one, which Cytoscape has no room for on an arc.
          label: edgeLabel(link, warning !== ''),
        };
        if (existing.length === 0) {
          // An arc can only be added once both endpoints exist; the node pass above guarantees it,
          // except for the moment between a node's delete and its links' - hence the guard.
          const hasEndpoints =
            cy.getElementById(nodeElementId(link.sourceNodeId)).length > 0 &&
            cy.getElementById(nodeElementId(link.targetNodeId)).length > 0;
          if (!hasEndpoints) {
            continue;
          }
          cy.add({ group: 'edges', data });
          continue;
        }
        // Only the presentational fields. Cytoscape will not repoint an existing edge through
        // `data()`, and links cannot be repointed anyway - the gesture is delete and redraw.
        existing.data({ warned: data['warned'], badge: data['badge'], label: data['label'] });
      }
    });
    this.syncing = false;
    this.applySelection(this.store.selectedNodeIds(), this.store.selectedLinkIds());
    // `nodeData` writes the default size, so the criticality encoding has to be re-stamped after
    // every render or an unrelated edit would quietly switch it off. Untracked: this runs
    // inside the render effect, and reading those two signals here would make every arriving metric
    // suite re-diff the whole canvas as well as resize it.
    untracked(() =>
      this.applyCriticality(this.metrics.criticality(), this.metrics.sizeByCriticality()),
    );
    // And the playback channels, for the same reason and on the same terms (FR-18). A
    // network being replayed is frozen, so a render during playback can only come from a
    // selection or a label change - but a re-stamp is one pass over the elements, and a canvas that
    // silently stops animating mid-run would be read as the animation having ended.
    untracked(() =>
      this.applyPlayback(
        this.playback.currentPeriod(),
        this.playback.elements(),
        this.playback.enabled(),
      ),
    );
  }

  // ------------------------------------------------------------ the captions (FR-30)

  /**
   * The captions of FR-30 on the canvas.
   *
   * > "the canvas draws a visible caption beneath the element's existing label, in a smaller and
   * > lower-contrast type: a node's under its name, a link's under the declared lead time it
   * > already shows."
   *
   * ## Why it is a second element rather than a style
   *
   * The constraint is a real one: **Cytoscape draws one label per element at one
   * font size**, which is already why an arc's label carries its lead time and nothing else (see
   * `edgeLabel`). A smaller, quieter line *beneath* that label is therefore not a variant of it -
   * it needs its own drawn thing. A companion Cytoscape node is the approach this canvas has
   * already taken once, for the corner drag handles, and it inherits pan and zoom for free where an
   * HTML overlay would have to be re-projected on every viewport change.
   *
   * ## What makes it safe to have a third kind of element on the canvas
   *
   * **This pass owns them, as deliberately as `render` owns the network.** It removes only ids
   * `isCaptionElementId` claims, so it can never reach a real element, a drag handle, or
   * edgehandles' ghost and preview; and `render` removes only ids that parse as a node or a link,
   * so it can never reach one of these. Both halves of that are what let either pass fire in the
   * middle of a drag, a box-select or an edge-draw without disturbing it.
   *
   * **And they are inert.** `events: 'no'` in the stylesheet means a caption is not hit-tested at
   * all: it is never a click target, a pointer press over one starts a box-select on the canvas
   * beneath exactly as if it were not there, and edgehandles never sees it. `selectable: false` and
   * `grabbable: false` say the same thing again at the element level, and `canConnect` rejects it a
   * third time because its id parses as no node - which is the same guard the handles rely on.
   *
   * The geometry is `element-captions.ts` and is specced; what is left here is adding, moving and
   * removing elements, which a headless test could only re-describe.
   */
  private syncCaptions(
    nodes: ReadonlyMap<Id, NetworkNode>,
    links: ReadonlyMap<Id, NetworkLink>,
  ): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    const wanted = captionElements(nodes.values(), links.values(), nodeElementId, edgeElementId);
    // Nothing to draw and nothing drawn: the ordinary state of a network nobody has annotated, and
    // it costs one map build.
    if (wanted.size === 0 && this.captionsWanted === 0) {
      return;
    }
    this.captionsWanted = wanted.size;
    this.syncing = true;
    cy.batch(() => {
      // The class and the caption id are written together at add time and nowhere else, so this
      // collection is exactly this feature's elements - the diff's whole claim to be safe.
      cy.$(`.${CAPTION_CLASS}`).forEach((element) => {
        if (!wanted.has(element.id())) {
          element.remove();
        }
      });
      for (const caption of wanted.values()) {
        const existing = cy.getElementById(caption.id);
        if (existing.length === 0) {
          this.addCaption(cy, caption);
          continue;
        }
        // Only the text. A caption never changes which element it belongs to - the id says which -
        // and its position belongs to `anchorCaption`, not to the store.
        if (existing.data('label') !== caption.text) {
          existing.data('label', caption.text);
        }
      }
    });
    this.syncing = false;
    this.repositionCaptions();
  }

  private addCaption(cy: cytoscape.Core, caption: CaptionElement): void {
    const anchor = this.captionAnchorFor(caption.ownerElementId);
    if (anchor === null) {
      // The element it describes is not on the canvas yet - the moment between a node's POST and
      // the render that draws it. The next pass adds the caption; adding it now would park it on
      // the origin for a frame.
      return;
    }
    cy.add({
      group: 'nodes',
      // `size` for the reason the drag handle carries one: the base `node` rule maps width and
      // height from data, and a node without the key leaves that mapper with nothing to read.
      data: { id: caption.id, label: caption.text, size: 1 },
      position: anchor,
      classes: CAPTION_CLASS,
      // Never selectable, never draggable. `events: 'no'` in the stylesheet already means no
      // gesture reaches it; these two are the same statement at the element level, so a caption
      // cannot be box-selected into the property panel's selection or dragged off its element.
      grabbable: false,
      selectable: false,
    });
  }

  /**
   * Re-anchor a moved node's own caption and those of every arc it is an endpoint of.
   *
   * Called from the `position` handler, so this is the hot path of a drag: one map lookup and at
   * most a position write per caption, and `anchorCaption` compares before it writes.
   */
  private followCaptions(node: cytoscape.NodeSingular): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    const owners = [node.id(), ...node.connectedEdges().map((edge) => edge.id())];
    const wasSyncing = this.syncing;
    this.syncing = true;
    cy.batch(() => {
      for (const ownerElementId of owners) {
        const caption = cy.getElementById(captionElementId(ownerElementId));
        if (caption.length > 0) {
          this.anchorCaption(caption as unknown as cytoscape.NodeSingular);
        }
      }
    });
    this.syncing = wasSyncing;
  }

  /**
   * Put every caption back under its element.
   *
   * Geometry only - it adds and removes nothing - which is what makes it safe to call from
   * `applyCriticality`, where the *size* of a node changed without the network changing at all.
   */
  private repositionCaptions(): void {
    const cy = this.cy;
    if (!cy || this.captionsWanted === 0) {
      return;
    }
    const captions = cy.$(`.${CAPTION_CLASS}`);
    if (captions.length === 0) {
      return;
    }
    const wasSyncing = this.syncing;
    this.syncing = true;
    cy.batch(() => {
      captions.forEach((element) =>
        this.anchorCaption(element as unknown as cytoscape.NodeSingular),
      );
    });
    this.syncing = wasSyncing;
  }

  private anchorCaption(caption: cytoscape.NodeSingular): void {
    const ownerElementId = captionOwnerElementId(caption.id());
    const anchor = ownerElementId === null ? null : this.captionAnchorFor(ownerElementId);
    if (anchor === null) {
      return;
    }
    if (hasMoved(caption.position(), anchor)) {
      caption.position(anchor);
    }
  }

  /**
   * Where the caption of one element goes, read from the canvas rather than from the store.
   *
   * From the canvas because both inputs are things only Cytoscape knows: a node's **outer height**,
   * which the criticality encoding writes per node, and an arc's **midpoint**, which for
   * two parallel arcs is not the midpoint of the line between their endpoints. The store holds
   * neither.
   */
  private captionAnchorFor(ownerElementId: string): cytoscape.Position | null {
    const cy = this.cy;
    if (!cy) {
      return null;
    }
    const owner = cy.getElementById(ownerElementId);
    if (owner.length === 0) {
      return null;
    }
    if (owner.isNode()) {
      return nodeCaptionAnchor(owner.position(), owner.outerHeight());
    }
    const midpoint = (owner as unknown as cytoscape.EdgeSingular).midpoint();
    // An arc whose endpoints have not been laid out yet answers with NaN, which Cytoscape would
    // take as a position and never recover from. Skipping leaves the caption where it is until the
    // next pass, which is one frame away.
    if (!Number.isFinite(midpoint?.x) || !Number.isFinite(midpoint?.y)) {
      return null;
    }
    return linkCaptionAnchor(midpoint);
  }

  /**
   * The node-size-by-criticality encoding.
   *
   * > "node size can encode live `NODE_CRITICALITY` so structural weak points are visible while
   * > editing."
   *
   * Size is written into element data rather than applied as a style rule per node, so the
   * stylesheet keeps one mapping and Cytoscape re-renders from data as it does for everything else.
   * With the toggle off, or for a node the suite has no value for, the size falls back to the
   * uniform default - a node whose criticality has not been computed must not look like a node whose
   * criticality is zero.
   */
  private applyCriticality(criticality: ReadonlyMap<Id, number>, encode: boolean): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    this.syncing = true;
    cy.batch(() => {
      cy.nodes().forEach((element) => {
        const id = nodeIdOf(element.id());
        if (id === null) {
          return;
        }
        const value = criticality.get(id);
        const size = encode && value !== undefined ? sizeFor(value) : NODE_SIZE;
        if (element.data('size') !== size) {
          element.data('size', size);
        }
      });
    });
    this.syncing = false;
    // A caption sits a fixed distance below the *bottom* of its node, so a diameter between 30 and
    // 74 px moves it (FR-30, `element-captions.nodeCaptionOffsetY`). Nothing else re-anchors here:
    // a size change fires no `position` event, so without this the encoding would leave captions
    // inside the nodes it grew and floating under the ones it shrank.
    this.repositionCaptions();
  }

  /**
   * The disruption overlay (FR-16).
   *
   * > "Events are drawn over the network they apply to … The badge uses a channel of its own: node
   * > colour already encodes type and node size may encode criticality, so a third meaning on either
   * > would make all three unreadable."
   *
   * The channel is the Cytoscape **underlay** - a halo drawn *behind* the element, which is why it
   * cannot be confused with anything else on the canvas. Colour is the node type, size is
   * criticality, border is selection, and a dashed orange line is an echelon warning; all four stay
   * exactly as legible with a halo under them as without one. Drawn behind rather than over on
   * purpose: an overlay would tint the body and take a bite out of the type colour.
   *
   * Written as element **data** with a class for the draft ring, so the stylesheet holds one rule per
   * state and Cytoscape re-renders from data as it does for everything else. Only changed elements
   * are touched - this runs on every node move, because moving a node changes the store's node map
   * and so re-derives the overlay.
   */
  private applyDisruptions(overlay: DisruptionOverlay, draftRegion: readonly Id[]): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    const drafted = new Set(draftRegion);
    this.syncing = true;
    cy.batch(() => {
      cy.nodes().forEach((element) => {
        const id = nodeIdOf(element.id());
        if (id === null) {
          return;
        }
        const disrupted = overlay.nodes.has(id);
        if (element.data('disrupted') !== disrupted) {
          element.data('disrupted', disrupted);
        }
        // The live preview of a region tag being typed. A separate class from the halo
        // above, because it answers a different question - what *would* be struck, if this draft
        // were saved - and merging them would show an unsaved draft as part of the scenario.
        element.toggleClass(REGION_DRAFT_CLASS, drafted.has(id));
      });
      cy.edges().forEach((element) => {
        const id = linkIdOf(element.id());
        if (id === null) {
          return;
        }
        const disrupted = overlay.links.has(id);
        if (element.data('disrupted') !== disrupted) {
          element.data('disrupted', disrupted);
        }
      });
    });
    this.syncing = false;
  }

  /**
   * One period of the run, on the canvas (FR-18).
   *
   * > "the network animates: inventory levels, flow volumes and disruption states move period by
   * > period."
   *
   * ## Three channels, each with exactly one meaning
   *
   * Every existing channel keeps the meaning it already had - colour is the node type, size may be
   * criticality, the border is the selection, a dashed orange arc is an echelon warning - so playback
   * had to find channels of its own rather than borrow theirs:
   *
   * - **Node inventory → the node's own fill.** `fillLevel` drives a linear-gradient with a hard
   *   boundary, so the node visibly fills bottom-up in a *stronger shade of its own type colour*.
   *   The hue is untouched, which is what keeps the echelon readable while the gauge moves.
   * - **Link flow → arc width**, 2 px to 9 px against that arc's own busiest period. An arc carrying
   *   nothing this period is drawn dashed and faded at the minimum width rather than removed: a
   *   stalled chain is a finding, and it is unreadable if the structure disappears with the flow.
   * - **Availability → the disruption underlay, made temporal.** See the handover note below.
   *
   * And one transient mark: a customer with `unserved > 0` this period carries the **overlay**
   * (`snrm-stockout`), the channel the region-tag preview borrows while a tag is being typed and
   * which is otherwise free. A stockout is exactly the kind of thing an overlay is for - loud, and
   * true only of this period.
   *
   * ## The underlay has one owner at a time
   *
   * FR-16's scenario-authoring halo and this availability tint are the same channel, and they answer
   * two different questions: *is this element struck somewhere in the horizon* against *is it dark
   * **now***. While playback runs the second one wins - the `snrm-playback` class sits after the
   * `[?disrupted]` rules in the stylesheet, and its opacity is 0 for a fully available element, so
   * the static halos go quiet without their data being touched. {@link clearPlayback} removes the
   * class and the halos come straight back, still driven by `applyDisruptions`' own data.
   * `disruption-overlay.ts` carries the same note from the other side.
   *
   * ## Per-element data, written only where it changed
   *
   * The criticality pattern: every visual quantity is element **data** and the stylesheet holds one
   * mapping, so Cytoscape re-renders from data as it does for everything else. Comparing before
   * writing matters more here than anywhere else in this file - this runs on every period of a run
   * playing at up to twenty periods a second, and on a steady-state network most elements have
   * nothing new to say from one period to the next.
   */
  private applyPlayback(period: number, elements: PlaybackElements | null, enabled: boolean): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    // Playback is off - or it is on and this run has no element detail (`available: false`),
    // in which case the clock and the transport still work and the canvas stays exactly as it is.
    // The transport bar is what says so; the canvas simply does not animate.
    if (!enabled || elements === null) {
      if (this.playbackPainted) {
        this.clearPlayback(cy);
      }
      return;
    }
    // A `const` so the narrowing above survives into the batch callbacks below.
    const channels = elements;

    this.playbackPainted = true;
    this.syncing = true;
    cy.batch(() => {
      cy.nodes().forEach((element) => {
        const id = nodeIdOf(element.id());
        const channel = id === null ? undefined : channels.nodes.get(id);
        if (!channel) {
          // A node the run has no series for - absent renders absent, so it keeps its ordinary
          // styling rather than a gauge reading empty.
          element.removeClass(PLAYBACK_CLASS);
          element.removeClass(STOCKOUT_CLASS);
          return;
        }
        const series = channel.series;
        const fill = fillLevel(valueAt(series.onHand, period), channel.maxOnHand);
        writeData(element, 'fillLevel', fill);
        writeData(element, 'fillStops', gaugeStops(fill));
        writeData(
          element,
          'fillColours',
          gaugeColours(String(element.data('colour')), String(element.data('accent'))),
        );
        writeData(element, 'availLoss', unavailableOpacity(valueAt(series.availability, period)));
        element.addClass(PLAYBACK_CLASS);
        // Only a customer has demand to leave unserved, so this is a customer marker in
        // practice - but it is derived from the number rather than from the type, because a node
        // type that gains demand later must not silently stop reporting a stockout.
        element.toggleClass(STOCKOUT_CLASS, valueAt(series.unserved, period) > 0);
      });

      cy.edges().forEach((element) => {
        const id = linkIdOf(element.id());
        const channel = id === null ? undefined : channels.links.get(id);
        if (!channel) {
          element.removeClass(PLAYBACK_CLASS);
          return;
        }
        const series = channel.series;
        // `flow` is what was **dispatched** this period; on an arc with a lead time it lands at the
        // target in a later period, which is why the ribbon and the target's gauge move
        // out of step. That offset is the mechanic, not a defect - see the sample's §6.5.3.
        const flow = valueAt(series.flow, period);
        writeData(element, 'flowW', flowWidth(flow, channel.maxFlow));
        writeData(element, 'flowStyle', flow > 0 ? 'solid' : 'dashed');
        writeData(element, 'flowOpacity', flow > 0 ? 1 : IDLE_LINK_OPACITY);
        writeData(element, 'availLoss', unavailableOpacity(valueAt(series.availability, period)));
        element.addClass(PLAYBACK_CLASS);
      });
    });
    this.syncing = false;
  }

  /**
   * Leave playback: every key and class this feature wrote, gone, and the canvas back to its rules.
   *
   * The re-stamp at the end is the same one {@link render} makes and for the same reason - the
   * criticality encoding is written as per-node data and nothing else re-applies it, so a path that
   * touches node data without re-stamping is a path that can strand a canvas showing uniform nodes
   * with the toggle on. Untracked, because this runs inside the playback effect and reading the
   * metrics signals here would make every arriving suite repaint the animation.
   */
  private clearPlayback(cy: cytoscape.Core): void {
    this.playbackPainted = false;
    this.syncing = true;
    cy.batch(() => {
      cy.nodes().forEach((element) => stripPlayback(element));
      cy.edges().forEach((element) => stripPlayback(element));
    });
    this.syncing = false;
    untracked(() =>
      this.applyCriticality(this.metrics.criticality(), this.metrics.sizeByCriticality()),
    );
  }

  private applySelection(nodeIds: ReadonlySet<Id>, linkIds: ReadonlySet<Id>): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    this.syncing = true;
    cy.batch(() => {
      cy.nodes().forEach((element) => {
        const id = nodeIdOf(element.id());
        const shouldSelect = id !== null && nodeIds.has(id);
        if (shouldSelect !== element.selected()) {
          if (shouldSelect) {
            element.select();
          } else {
            element.unselect();
          }
        }
      });
      cy.edges().forEach((element) => {
        const id = linkIdOf(element.id());
        const shouldSelect = id !== null && linkIds.has(id);
        if (shouldSelect !== element.selected()) {
          if (shouldSelect) {
            element.select();
          } else {
            element.unselect();
          }
        }
      });
    });
    this.syncing = false;
  }

  private applyModes(readOnly: boolean, boxSelect: boolean): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    cy.autoungrabify(readOnly);
    // With panning off, a plain background drag rubber-bands instead of moving the viewport.
    cy.userPanningEnabled(!boxSelect);
    if (readOnly) {
      this.eh?.disable();
    } else {
      this.eh?.enable();
    }
  }

  /**
   * Where a node sits on the canvas.
   *
   * A node with no coordinates - every node of a freshly imported network - is parked on
   * an echelon-columned grid rather than at the origin, so an un-laid-out network is legible before
   * the auto-layout button is pressed. The parking spot is remembered so repeated renders do not
   * shuffle it, and it is deliberately *not* persisted: `posX`/`posY` stay null until the user drags
   * the node or runs the layout, which is what keeps "manual positions take precedence" meaningful.
   */
  private positionFor(node: NetworkNode): cytoscape.Position {
    if (node.posX !== null && node.posY !== null) {
      return { x: node.posX, y: node.posY };
    }
    const parked = this.fallbackPositions.get(node.id);
    if (parked) {
      return parked;
    }
    const position: cytoscape.Position = {
      x: 120 + ECHELON_RANK[node.type] * FALLBACK_COLUMN,
      y: 100 + (this.fallbackSeq++ % 12) * FALLBACK_ROW,
    };
    this.fallbackPositions.set(node.id, position);
    return position;
  }
}

// ------------------------------------------------------------------------- helpers

/**
 * Handler shape for the `eh*` events. Later arguments are absent on `ehstart`/`ehstop`, so they are
 * optional; a handler that takes fewer parameters is assignable either way.
 */
type EdgeHandlesHandler = (
  event: cytoscape.EventObject,
  sourceNode: cytoscape.NodeSingular,
  targetNode: cytoscape.NodeSingular,
  addedEdge?: cytoscape.EdgeSingular,
) => void;

/**
 * Subscribes to an extension event with its extra arguments typed.
 *
 * Cytoscape's own `EventHandler` types everything after the event object as `any[]`, because the
 * core cannot know what an extension passes. This restores the shape edgehandles actually sends, in
 * one place, instead of leaving four call sites to cast their own arguments.
 */
function onEdgeHandlesEvent(
  cy: cytoscape.Core,
  events: string,
  handler: EdgeHandlesHandler,
): void {
  const subscribe = cy.on as unknown as (names: string, handler: EdgeHandlesHandler) => void;
  subscribe.call(cy, events, handler);
}

function nodeElementId(id: Id): string {
  return `${NODE_ELEMENT_PREFIX}${id}`;
}

function edgeElementId(id: Id): string {
  return `${EDGE_ELEMENT_PREFIX}${id}`;
}

/** True for one of the corner drag handles rather than a real element. */
function isHandleId(elementId: string | undefined | null): boolean {
  return !!elementId && elementId.startsWith(HANDLE_ID_PREFIX);
}

function nodeIdOf(elementId: string | undefined | null): Id | null {
  return parseElementId(elementId, NODE_ELEMENT_PREFIX);
}

function linkIdOf(elementId: string | undefined | null): Id | null {
  return parseElementId(elementId, EDGE_ELEMENT_PREFIX);
}

/**
 * Element id to store id, or null for anything that is not ours.
 *
 * Tolerates a missing id, and must: `makeEdges` asks `canConnect` about the target *before* it
 * checks whether there is one, so a gesture released over empty canvas arrives here with the
 * `id()` of an empty collection - `undefined`. Throwing there would abort `stop()` half-way and
 * strand the rubber band on the canvas with the gesture still active.
 */
function parseElementId(elementId: string | undefined | null, prefix: string): Id | null {
  if (!elementId || !elementId.startsWith(prefix)) {
    return null;
  }
  const id = Number(elementId.slice(prefix.length));
  return Number.isFinite(id) ? id : null;
}

/** Distance in rendered pixels between a node's top edge and its tooltip. */
const TOOLTIP_GAP = 10;

/**
 * What the arc says on the canvas: its declared lead time, prefixed by the echelon badge.
 *
 * "6 h", not "0 periods" - the number the researcher entered, in the unit they entered it in.
 * A zero-transit arc says "0 d" rather than nothing, because an arc that delivers in the
 * period it ships is a modelling choice worth seeing.
 */
function edgeLabel(link: NetworkLink, warned: boolean): string {
  const declared = formatDuration(link.leadTime);
  return warned ? `⚠ ${declared}` : declared;
}

/**
 * The tooltip body for a node: the two unit-bearing figures, then the region if it has one.
 *
 * Kept to what units make ambiguous. Costs and probabilities are plain numbers and are one click
 * away in the property panel.
 */
function nodeTooltipLines(node: NetworkNode): readonly string[] {
  const lines = [
    `Processing time ${formatDuration(node.processingTime)}`,
    `Capacity ${formatRate(node.capacity)}`,
  ];
  if (node.region) {
    lines.push(`Region ${node.region}`);
  }
  return lines;
}

/**
 * The one colour disruption speaks in, on the canvas and in the panel's event rows.
 *
 * Red rather than the echelon warning's orange: an unconventional arc is a modelling choice worth
 * noticing, an event is something being done to the network, and the two must not read as degrees of
 * the same thing.
 */
const DISRUPTION_COLOUR = '#dc3545';

/** Nodes a region tag currently being typed resolves to - see the stylesheet rule. */
const REGION_DRAFT_CLASS = 'snrm-region-draft';

/**
 * Carried by every element being animated (FR-18).
 *
 * Its rules sit **after** the `[?disrupted]` halo rules in the stylesheet and **before** the
 * selection and echelon-warning ones, which is the whole of the precedence design: playback takes
 * over the underlay while it runs, and never takes over a channel that means something else.
 */
const PLAYBACK_CLASS = 'snrm-playback';

/** A customer with unmet demand in the period on screen. The transient overlay channel (FR-18). */
const STOCKOUT_CLASS = 'snrm-stockout';

/** Every data key playback writes, so leaving it can put the elements back as it found them. */
const PLAYBACK_DATA_KEYS: readonly string[] = [
  'fillLevel',
  'fillStops',
  'fillColours',
  'availLoss',
  'flowW',
  'flowStyle',
  'flowOpacity',
];

/** Colour of the stockout overlay - amber, deliberately not the red disruption is spoken in. */
const STOCKOUT_COLOUR = '#fd7e14';

/**
 * Writes one data key, and only when the value actually changed.
 *
 * The criticality pattern, applied where it matters most: `applyPlayback` runs once per period of a
 * run playing at up to twenty periods a second, and an unchanged write still marks the element dirty
 * and costs a re-render of it.
 *
 * The cast narrows a union whose two members declare `data` identically - Cytoscape's typings model
 * `SingularElementArgument` as `EdgeSingular | NodeSingular`, and a two-argument call against a
 * union of overloads does not resolve. Nothing node-specific is reached through it.
 */
function writeData(element: cytoscape.SingularElementArgument, key: string, value: unknown): void {
  const target = element as cytoscape.NodeSingular;
  if (target.data(key) !== value) {
    target.data(key, value);
  }
}

/** Everything playback wrote on one element, removed. See {@link writeData} for the cast. */
function stripPlayback(element: cytoscape.SingularElementArgument): void {
  const target = element as cytoscape.NodeSingular;
  target.removeClass(`${PLAYBACK_CLASS} ${STOCKOUT_CLASS}`);
  for (const key of PLAYBACK_DATA_KEYS) {
    if (target.data(key) !== undefined) {
      target.removeData(key);
    }
  }
}

/** Diameter of a node with the criticality encoding off - the uniform default. */
const NODE_SIZE = 46;

/** Diameter of the least and most critical node when the encoding is on. */
const NODE_SIZE_MIN = 30;
const NODE_SIZE_MAX = 74;

/**
 * A criticality in [0,1] as a node diameter.
 *
 * Linear, and anchored so that the *smallest* size means "removing this changes nothing" rather
 * than "no data". The range is deliberately wide: the encoding exists to make a weak point visible
 * at a glance across a canvas, and a 10% size difference is not visible at a glance.
 */
function sizeFor(criticality: number): number {
  const clamped = Math.min(1, Math.max(0, criticality));
  return NODE_SIZE_MIN + (NODE_SIZE_MAX - NODE_SIZE_MIN) * clamped;
}

function nodeData(node: NetworkNode): cytoscape.NodeDataDefinition {
  const profile = nodeTypeProfile(node.type);
  return {
    id: nodeElementId(node.id),
    name: node.name,
    type: node.type,
    colour: profile.colour,
    accent: profile.accent,
    shape: profile.shape,
    // Overwritten by `applyCriticality` when the encoding is on. Present here so a node
    // added mid-session has a size from its first frame rather than collapsing to Cytoscape's
    // default until the next criticality effect runs.
    size: NODE_SIZE,
  };
}

/** Half a pixel of tolerance keeps float noise from re-writing a position on every render. */
function hasMoved(current: cytoscape.Position, next: cytoscape.Position): boolean {
  return Math.abs(current.x - next.x) > 0.5 || Math.abs(current.y - next.y) > 0.5;
}

/**
 * Canvas stylesheet.
 *
 * Node colour encodes type and the shape repeats it, so the four echelons stay
 * distinguishable without relying on hue alone.
 */
function canvasStyle(): cytoscape.StylesheetJson {
  // Cast once, at the end. Cytoscape's `data(...)` mappers are strings at runtime but the shipped
  // css types model each property as its literal union - `shape: 'data(shape)'` cannot be expressed
  // in them. Casting the array beats scattering per-property casts through it.
  const style = [
    {
      selector: 'node',
      style: {
        'background-color': 'data(colour)',
        'border-color': 'data(accent)',
        'border-width': 2,
        shape: 'data(shape)',
        label: 'data(name)',
        // Uniform unless the criticality encoding is on, in which case `applyCriticality`
        // has already written a per-node diameter into this field.
        width: 'data(size)',
        height: 'data(size)',
        'font-size': 11,
        'font-family': 'system-ui, sans-serif',
        color: '#212529',
        'text-valign': 'bottom',
        'text-margin-y': 6,
        'text-background-color': '#f6f7f9',
        'text-background-opacity': 0.85,
        'text-background-padding': '2px',
        'overlay-opacity': 0,
        // Above the caption elements of FR-30, which state 0 - so a caption that reaches under a
        // neighbouring node passes behind it rather than over it (never in front of the
        // element it describes). Edges are drawn under nodes regardless, by `z-index-compare`'s
        // default, so nothing else on the canvas changes.
        'z-index': 1,
      },
    },
    {
      // The caption of FR-30: the second line, on a companion node that draws nothing of
      // itself and exists only to carry a label Cytoscape cannot hang on the element it describes.
      //
      // **Smaller and lower-contrast than the name above it**, which is a requirement rather than a
      // suggestion: 9 px against 11, and #6c757d against the name's #212529. An element is identified
      // by its name everywhere else in the tool, and a second line at the same weight would put two
      // identities on one element.
      //
      // **`events: 'no'` is the whole of "non-interactive"**, and it is a stronger statement than
      // `selectable: false`: the element is not hit-tested at all, so it is never a click target,
      // never hovered, never a box-select candidate, never seen by edgehandles' snap, and a pointer
      // press over it starts a drag on the canvas underneath exactly as if it were not there.
      //
      // **`z-index: 0` against the `node` rule's 1** keeps it behind the elements it annotates. It
      // is positioned clear of them, so this only decides what happens where a caption reaches
      // under a neighbouring node - and "behind" is the required answer.
      selector: `.${CAPTION_CLASS}`,
      style: {
        label: 'data(label)',
        width: 1,
        height: 1,
        shape: 'ellipse',
        'background-opacity': 0,
        'border-width': 0,
        'font-size': CAPTION_FONT_SIZE,
        'font-family': 'system-ui, sans-serif',
        color: '#6c757d',
        // Anchored at the TOP of the caption's box (`element-captions.nodeCaptionOffsetY`), so a
        // caption long enough to wrap grows downward, away from the label above it.
        'text-valign': 'bottom',
        'text-halign': 'center',
        'text-margin-y': 0,
        'text-wrap': 'wrap',
        'text-max-width': 170,
        'text-background-color': '#f6f7f9',
        'text-background-opacity': 0.8,
        'text-background-padding': `${LABEL_PADDING}px`,
        'z-index': 0,
        events: 'no',
        'overlay-opacity': 0,
      },
    },
    {
      selector: 'node:selected',
      style: {
        'border-color': '#0d6efd',
        'border-width': 5,
      },
    },
    {
      // The disruption badge (FR-16), on a channel nothing else uses: an **underlay**,
      // drawn behind the element. Colour is the node type, size is criticality, the border is the
      // selection and a dashed orange line is an echelon warning - a halo collides with none of them,
      // and a node can carry all four at once and stay readable.
      selector: 'node[?disrupted]',
      style: {
        'underlay-color': DISRUPTION_COLOUR,
        'underlay-opacity': 0.3,
        'underlay-padding': 9,
      },
    },
    {
      selector: 'edge[?disrupted]',
      style: {
        'underlay-color': DISRUPTION_COLOUR,
        'underlay-opacity': 0.3,
        'underlay-padding': 5,
      },
    },
    {
      // What a region tag being typed *would* strike: the nodes the server says the tag
      // covers, lit while the choice is being made.
      //
      // The **overlay** - the one channel left, and the right one for this. It is transient by
      // nature (it lasts as long as the picker is open), it is loud, and it is not the halo, so a
      // draft cannot be mistaken for an event already in the scenario. It deliberately does not use
      // the border: that is the selection, and a previewed node may well be selected.
      selector: `.${REGION_DRAFT_CLASS}`,
      style: {
        'overlay-color': DISRUPTION_COLOUR,
        'overlay-opacity': 0.25,
        'overlay-padding': 6,
      },
    },
    {
      // Visual playback, node channel (FR-18). The gauge is a gradient over the node's own
      // two palette colours - accent below the line, type colour above it - so the fill is a second
      // reading of the hue that already encodes the echelon rather than a new colour to learn. Four
      // stops with the middle two coincident make it a line rather than a fade (`gaugeStops`).
      //
      // **This rule is placed after `node[?disrupted]` on purpose**: its `underlay-opacity` is the
      // temporal availability tint, and it must win over the static scenario halo while a run plays.
      // At full availability that opacity is 0, which is what makes the handover visible - the halos
      // go quiet the moment playback starts and come back the moment the class is removed.
      selector: `node.${PLAYBACK_CLASS}`,
      style: {
        'background-fill': 'linear-gradient',
        'background-gradient-direction': 'to-top',
        'background-gradient-stop-colors': 'data(fillColours)',
        'background-gradient-stop-positions': 'data(fillStops)',
        'underlay-color': DISRUPTION_COLOUR,
        'underlay-opacity': 'data(availLoss)',
        'underlay-padding': 9,
      },
    },
    {
      // A customer that wanted something this period and did not get it. The **overlay** - the
      // transient channel, free during playback because the region-tag preview it otherwise carries
      // only exists while a tag is being typed in the disruptions panel.
      selector: `node.${STOCKOUT_CLASS}`,
      style: {
        'overlay-color': STOCKOUT_COLOUR,
        'overlay-opacity': 0.32,
        'overlay-padding': 7,
      },
    },
    {
      selector: 'node.link-target-valid',
      style: {
        'border-color': '#198754',
        'border-width': 6,
      },
    },
    {
      selector: 'node.link-target-invalid',
      style: {
        'border-color': '#dc3545',
        'border-width': 6,
        opacity: 0.65,
      },
    },
    {
      selector: 'edge',
      style: {
        width: 2,
        'line-color': '#8b96a3',
        'target-arrow-color': '#8b96a3',
        'target-arrow-shape': 'triangle',
        'arrow-scale': 1.1,
        'curve-style': 'bezier',
        'overlay-opacity': 0,
        // The declared lead time, in its own unit. Rotated with the arc so parallel links
        // in a dense echelon do not stack their labels on top of one another.
        label: 'data(label)',
        'font-size': 10,
        'font-family': 'system-ui, sans-serif',
        color: '#495057',
        'edge-text-rotation': 'autorotate',
        'text-background-color': '#ffffff',
        'text-background-opacity': 0.85,
        'text-background-padding': '2px',
      },
    },
    {
      // Visual playback, link channel (FR-18): width is this arc's flow against its own
      // busiest period, and an arc carrying nothing is dashed and faded at the minimum width rather
      // than hidden - a stalled chain is a finding, and it is unreadable if the arcs vanish with the
      // flow. The underlay is the same temporal availability tint the node rule carries.
      //
      // **Placed before `edge:selected` and `edge[?warned]`**, which is what keeps those two saying
      // what they have always said: a selected arc stays blue and thick, and an echelon-warning arc
      // stays orange and dashed, whatever the flow is doing under them.
      selector: `edge.${PLAYBACK_CLASS}`,
      style: {
        width: 'data(flowW)',
        'line-style': 'data(flowStyle)',
        opacity: 'data(flowOpacity)',
        'underlay-color': DISRUPTION_COLOUR,
        'underlay-opacity': 'data(availLoss)',
        'underlay-padding': 5,
      },
    },
    {
      selector: 'edge:selected',
      style: {
        'line-color': '#0d6efd',
        'target-arrow-color': '#0d6efd',
        width: 4,
      },
    },
    {
      // The warning badge - lateral, upstream and echelon-skipping arcs are legal but
      // flagged, so an unconventional topology is visible rather than merely permitted. The ⚠ is
      // part of the label (see `edgeLabel`); this is what tints it.
      selector: 'edge[?warned]',
      style: {
        'line-color': '#fd7e14',
        'target-arrow-color': '#fd7e14',
        'line-style': 'dashed',
        color: '#8a4b00',
        'text-background-color': '#fff4e6',
        'text-background-opacity': 0.9,
      },
    },
    {
      // Our own corner drag handles (see HANDLE_CORNERS). edgehandles v4 has no handle of its own,
      // so there is no `.eh-handle` class to style - that was v3.
      selector: '.snrm-eh-handle',
      style: {
        'background-color': '#0d6efd',
        'border-color': '#ffffff',
        'border-width': 2,
        width: HANDLE_SIZE,
        height: HANDLE_SIZE,
        shape: 'ellipse',
        label: '',
        'z-index': 9999,
        'overlay-opacity': 0,
      },
    },
    {
      selector: '.eh-source, .eh-target',
      style: {
        'border-color': '#0d6efd',
        'border-width': 4,
      },
    },
    {
      selector: '.eh-preview, .eh-ghost-edge',
      style: {
        'line-color': '#0d6efd',
        'target-arrow-color': '#0d6efd',
        'line-style': 'dashed',
        'target-arrow-shape': 'triangle',
      },
    },
  ];

  return style as unknown as cytoscape.StylesheetJson;
}
