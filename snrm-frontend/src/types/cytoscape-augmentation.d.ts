/**
 * Augments Cytoscape's own bundled typings (`node_modules/cytoscape/index.d.ts`) with the surface
 * the two extensions add at runtime.
 *
 * The `export {}` below is load-bearing: it makes this file a module, which is what turns
 * `declare module 'cytoscape'` into an *augmentation* of the installed types instead of a fresh
 * ambient declaration that would shadow them entirely.
 *
 * Only what `graph-canvas.component.ts` actually calls is declared, so a runtime/typing mismatch
 * surfaces as a compile error in one place rather than as a silent `any`.
 */
export {};

declare module 'cytoscape' {
  /** Layout options accepted by `cytoscape-dagre`. */
  interface DagreLayoutOptions extends BaseLayoutOptions {
    name: 'dagre';
    /** Layer direction. The editor lays echelons out left to right. */
    rankDir?: 'TB' | 'BT' | 'LR' | 'RL';
    /** Separation between adjacent nodes in the same rank, in pixels. */
    nodeSep?: number;
    /** Separation between adjacent edges in the same rank, in pixels. */
    edgeSep?: number;
    /** Separation between ranks, in pixels. */
    rankSep?: number;
    /** Heuristic used to assign ranks. */
    ranker?: 'network-simplex' | 'tight-tree' | 'longest-path';
    fit?: boolean;
    padding?: number;
    animate?: boolean;
    animationDuration?: number;
    spacingFactor?: number;
  }

  /** The subset of `cytoscape-edgehandles` options the editor sets. */
  interface EdgeHandlesOptions {
    /** Gesture-level validity gate (echelon-aware, no self-loops, no duplicates). */
    canConnect?(sourceNode: NodeSingular, targetNode: NodeSingular): boolean;
    /** Data for the provisional edge drawn during the gesture. */
    edgeParams?(sourceNode: NodeSingular, targetNode: NodeSingular): Record<string, unknown>;
    /** Milliseconds a pointer must rest on a node before it counts as hovered. */
    hoverDelay?: number;
    /** Snap the rubber band to the nearest node under the pointer. */
    snap?: boolean;
    snapThreshold?: number;
    snapFrequency?: number;
    /** Suppress normal edge events while a draw gesture is in progress. */
    noEdgeEventsInDraw?: boolean;
    /** Stop the browser's own drag/scroll gestures from firing over the canvas. */
    disableBrowserGestures?: boolean;
    /** Selector for the nodes a gesture may start from. */
    handleNodes?: string;
  }

  /** The instance `cy.edgehandles(...)` returns. */
  interface EdgeHandlesInstance {
    /**
     * Every pointer-down on a node starts a draw.
     *
     * Not used here: v4 implements draw mode by calling `cy.autoungrabify(true)`, which trades away
     * node dragging for the whole time it is on. The editor needs both gestures, so it drives
     * {@link start} from its own hover handle instead.
     */
    enableDrawMode(): void;
    disableDrawMode(): void;
    enable(): void;
    disable(): void;
    /**
     * Begin a draw from `sourceNode`.
     *
     * Only produces a rubber band when Cytoscape is already tracking a drag - the extension's
     * `update` is driven by `tapdrag` and its `stop` by `tapend` - so call this from a `tapstart`
     * handler on an element inside the canvas, never from a DOM listener outside it.
     */
    start(sourceNode: NodeSingular): void;
    /** Abandon the gesture in progress. */
    stop(): void;
    destroy(): void;
  }

  interface Core {
    edgehandles(options?: EdgeHandlesOptions): EdgeHandlesInstance;
  }
}
