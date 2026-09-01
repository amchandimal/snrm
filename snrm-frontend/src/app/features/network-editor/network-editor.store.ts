import { Injectable, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import {
  WireLink,
  WireNode,
  WireRun,
  normaliseLink,
  normaliseLinks,
  normaliseNode,
  normaliseNodes,
  normaliseRuns,
} from '../../core/api-nulls';
import { ApiService } from '../../core/api.service';
import { ForkRequest } from '../../core/fork-request';
import {
  Duration,
  Id,
  LinkAttributesRequest,
  LinkRequest,
  Network,
  NetworkLink,
  NetworkNode,
  NodeProduct,
  NodeProductRequest,
  NodeRequest,
  NodeType,
  ProblemCode,
  Product,
  SimulationRun,
  TimeBaseRequest,
  TimeBaseResponse,
  TimeElementType,
  TimeFinding,
  TimeUnit,
  TimeValidationReport,
} from '../../core/models';
import { NetworkCloneService } from '../../core/network-clone.service';
import { problemCode, problemMessage } from '../../core/problem-details';
import {
  DiscardConfirm,
  activeRunsBlocker,
  discardNetworkRunsConfirm,
} from '../../core/run-discard';
import { sameFieldValue } from '../../core/time-units';
import {
  AddLinkCommand,
  AddNodeCommand,
  CommandContext,
  CommandStack,
  DeleteElementsCommand,
  EditLinksCommand,
  EditNodesCommand,
  EditorCommand,
  LinkAttributes,
  LinkEdit,
  MoveNodesCommand,
  NodeAttributes,
  NodeEdit,
  NodeMove,
  RemoveNodeProductCommand,
  ReplaceLinkCommand,
  ReplaceNodeCommand,
  UpsertNodeProductCommand,
  resolveRemapped,
  toNodeProductRequest,
  toNodeRequest,
} from './editor-commands';
import { EditorPersistenceService } from './editor-persistence.service';
import { previousAttributeValue } from './element-captions';
import {
  LinkRefusal,
  LinkVerdict,
  LinkWarning,
  defaultLinkRequest,
  defaultNodeRequest,
  linkPairKeys,
  linkVerdict,
  nextNodeName,
  warningForLink,
} from './echelon-rules';
import { UnitPreferencesService } from './unit-preferences.service';

/**
 * {@link ForkRequest} is `core/fork-request.ts` and is re-exported here.
 *
 * It was declared on this store while the editor was the only thing that forked a network - types
 * flow store → component, so the dialog collecting it takes the store's type rather than the other
 * way round. FR-26 gave it a second builder on the project dashboard (*Duplicate network* on a
 * row), which is the "two features, one fact" case `core/lever-changes.ts` and `core/run-discard.ts`
 * already answer, so the type and the two rules that resolve it moved to `core/`. The re-export
 * keeps that a move rather than a change of this store's contract: `fork()` still takes a
 * `ForkRequest`, and the editor's own imports are unchanged.
 */
export type { ForkRequest };

/** Lifecycle of the editor's initial load. Mirrors `LoadState` in the projects feature. */
export type EditorLoadState = 'idle' | 'loading' | 'ready' | 'error';

/** What the property side panel should show for the current selection. */
export type SelectionKind = 'none' | 'node' | 'nodes' | 'link' | 'links' | 'mixed';

/** What the delete confirm lists before it removes anything. */
export interface DeletionImpact {
  readonly nodeCount: number;
  readonly linkCount: number;
  /** Lines for `ConfirmDialogComponent`'s `details` input. */
  readonly details: readonly string[];
}

/**
 * Signal state for the network editor.
 *
 * ## The store is the truth; the canvas is a projection
 *
 * `nodes` and `links` here are authoritative. `graph-canvas.component.ts` reconciles Cytoscape
 * against them in one effect and never mutates them directly - every gesture it observes turns into
 * a command instead. Pan, zoom and the rubber band stay inside Cytoscape and out of signals, since
 * routing a wheel event through change detection is how a graph canvas starts to feel bad.
 *
 * ## Not `providedIn: 'root'`
 *
 * Unlike `ProjectsStore` and `NetworksStore` this is provided on the route component, so the whole
 * editing session - undo stack, selection, pending writes - is discarded when the user leaves.
 * A stale undo stack pointing at another network's ids is worse than no undo stack.
 *
 * ## Time is state here too
 *
 * The store owns three things units brought with them: the network's **clock** (`periodLength`,
 * which every duration dropdown defaults to and every restatement divides by), the **resolution
 * report** behind the warning banner, and the **per-product rows** of the selected node. All three
 * are derived from the server rather than computed here - the checks run over every
 * duration in the network, not the ones on screen - and all three are refreshed on the same signal:
 * an edit landed.
 */
@Injectable()
export class NetworkEditorStore implements CommandContext, OnDestroy {
  private readonly api = inject(ApiService);
  /** The one caller of `POST /networks/{id}/clone`, shared with the dashboard's Duplicate (FR-26). */
  private readonly clones = inject(NetworkCloneService);
  readonly persistence = inject(EditorPersistenceService);
  readonly units = inject(UnitPreferencesService);

  readonly stack = new CommandStack();

  private readonly _networkId = signal<Id | null>(null);
  private readonly _network = signal<Network | null>(null);
  private readonly _nodes = signal<ReadonlyMap<Id, NetworkNode>>(new Map());
  private readonly _links = signal<ReadonlyMap<Id, NetworkLink>>(new Map());
  private readonly _state = signal<EditorLoadState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _actionError = signal<string | null>(null);

  /** The project's product catalogue, for the panel's per-product rows (project-scoped). */
  private readonly _products = signal<readonly Product[]>([]);
  /** Whether the panel's "new product" shortcut is mid-write. See {@link createProduct}. */
  private readonly _creatingProduct = signal(false);
  /** Per-product rows, keyed by node. Loaded lazily: only a single selected node shows them. */
  private readonly _nodeProducts = signal<ReadonlyMap<Id, readonly NodeProduct[]>>(new Map());

  /** The resolution report, refreshed after edits and after a time-base change. */
  private readonly _timeValidation = signal<TimeValidationReport | null>(null);
  private readonly _timeValidationBusy = signal(false);
  /** Fingerprint of the findings the user dismissed; a new problem raises the banner again. */
  private readonly _dismissedFindings = signal<string | null>(null);
  private readonly _timeBaseBusy = signal(false);

  private readonly _selectedNodeIds = signal<ReadonlySet<Id>>(new Set());
  private readonly _selectedLinkIds = signal<ReadonlySet<Id>>(new Set());

  private readonly _lastUsedType = signal<NodeType>('SUPPLIER');
  private readonly _gridSnap = signal(false);
  private readonly _boxSelect = signal(false);
  private readonly _forkPromptOpen = signal(false);
  private readonly _forking = signal(false);

  /** This network's simulation runs - the freeze itself, in row form (FR-20, FR-21). */
  private readonly _runs = signal<readonly SimulationRun[]>([]);
  private readonly _loadingRuns = signal(false);
  /** Why the last read of that list failed, or null - see {@link loadNetworkRuns} (FR-21). */
  private readonly _runsError = signal<string | null>(null);
  private readonly _discardPromptOpen = signal(false);
  private readonly _discarding = signal(false);
  /** True when the loaded network had no coordinates at all - the auto-layout case. */
  private readonly _needsInitialLayout = signal(false);

  /**
   * Bumped by every edit that could change a topological metric.
   *
   * `TopologicalMetricsStore` watches it to know when the suite it is showing has stopped
   * describing the network. A counter rather than a boolean because it also has to be able to
   * cancel an in-flight recompute that a later edit has already invalidated.
   *
   * Moves are excluded. Every metric in the topological suite is a function of the arcs and the
   * capacities; a node's canvas coordinates appear in none of them, and dragging a selection across
   * the canvas must not queue a recompute per drag.
   */
  private readonly _topologyRevision = signal(0);

  /** Node ids re-created by an undo, mapped from the id they used to hold. See `editor-commands.ts`. */
  private readonly nodeRemap = new Map<Id, Id>();
  private readonly linkRemap = new Map<Id, Id>();

  readonly network = this._network.asReadonly();
  readonly nodes = this._nodes.asReadonly();
  readonly links = this._links.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  /** Last failure from a gesture, shown as a dismissible banner over the canvas. */
  readonly actionError = this._actionError.asReadonly();
  readonly selectedNodeIds = this._selectedNodeIds.asReadonly();
  readonly selectedLinkIds = this._selectedLinkIds.asReadonly();
  readonly lastUsedType = this._lastUsedType.asReadonly();
  readonly gridSnap = this._gridSnap.asReadonly();
  readonly boxSelect = this._boxSelect.asReadonly();
  readonly forkPromptOpen = this._forkPromptOpen.asReadonly();
  readonly forking = this._forking.asReadonly();
  /**
   * Every run of this network, newest first, as last read (FR-20, FR-21).
   *
   * Two readers, one list - which is the point of the endpoint: the FR-20 discard
   * confirmation counts and names what will go from it, and the run panel's history *is* it
   * rendered. A second copy would let the panel offer a run the dialog had already destroyed.
   */
  readonly runs = this._runs.asReadonly();
  readonly loadingRuns = this._loadingRuns.asReadonly();
  /** Why the run list could not be read, or null. The run panel's history renders it (FR-21). */
  readonly runsError = this._runsError.asReadonly();
  readonly discardPromptOpen = this._discardPromptOpen.asReadonly();
  readonly discarding = this._discarding.asReadonly();
  readonly needsInitialLayout = this._needsInitialLayout.asReadonly();
  /** See {@link _topologyRevision}. */
  readonly topologyRevision = this._topologyRevision.asReadonly();

  /** Grid pitch in model pixels when snap is on. */
  readonly gridSize = 20;

  readonly products = this._products.asReadonly();
  /** True while the panel's "new product" shortcut has a write in flight. */
  readonly creatingProduct = this._creatingProduct.asReadonly();
  readonly timeValidation = this._timeValidation.asReadonly();
  readonly timeValidationBusy = this._timeValidationBusy.asReadonly();
  readonly timeBaseBusy = this._timeBaseBusy.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');
  readonly nodeList = computed(() => [...this._nodes().values()]);
  readonly linkList = computed(() => [...this._links().values()]);

  /**
   * The network's clock, with a stand-in until the network has loaded.
   *
   * The fallback never reaches the server: every write that could use it is gated on a loaded
   * network. It exists so the property panel can render its dropdowns during the first frame
   * without every caller writing the same null check.
   */
  readonly periodLength = computed<Duration>(
    () => this._network()?.periodLength ?? { value: 1, unit: 'DAY' },
  );

  /** The unit every duration field opens on. */
  readonly periodUnit = computed<TimeUnit>(() => this.periodLength().unit);

  /** Findings of the current report, worst first. Empty when the clock costs nothing. */
  readonly timeFindings = computed(() => this._timeValidation()?.findings ?? []);

  /**
   * Whether the resolution banner is showing.
   *
   * Dismissal is remembered against the *content* of the report, not as a flag: dismissing "6 h
   * rounds to nothing" should not also silence the different warning that appears two edits later,
   * and re-raising the identical warning the user has already read would be nagging.
   */
  readonly timeWarningsVisible = computed(
    () =>
      this.timeFindings().length > 0 &&
      this._dismissedFindings() !== fingerprint(this.timeFindings()),
  );

  /**
   * False once a simulation run has frozen the network.
   *
   * Read before the user's first gesture so the editor can offer the fork rather than let an edit
   * fail - which is exactly why `NetworkDto` carries the flag.
   */
  readonly readOnly = computed(() => this._network() !== null && !this._network()?.editable);

  /**
   * What the discard confirmation should say, from the runs last read (FR-20).
   *
   * Derived rather than snapshotted when the dialog opens, so a re-read that arrives while it is
   * open corrects the count instead of leaving a stale number on screen above a Delete button.
   */
  readonly discardConfirm = computed<DiscardConfirm | null>(() => {
    const network = this._network();
    return network === null ? null : discardNetworkRunsConfirm(network, this._runs());
  });

  readonly selectedNodes = computed(() => {
    const nodes = this._nodes();
    return [...this._selectedNodeIds()].map((id) => nodes.get(id)).filter(isPresent);
  });

  readonly selectedLinks = computed(() => {
    const links = this._links();
    return [...this._selectedLinkIds()].map((id) => links.get(id)).filter(isPresent);
  });

  readonly selectionKind = computed<SelectionKind>(() => {
    const nodes = this.selectedNodes().length;
    const links = this.selectedLinks().length;
    if (nodes && links) {
      return 'mixed';
    }
    if (nodes) {
      return nodes === 1 ? 'node' : 'nodes';
    }
    if (links) {
      return links === 1 ? 'link' : 'links';
    }
    return 'none';
  });

  /** Ordered pairs already connected - the duplicate check of {@link linkVerdict}. */
  readonly pairKeys = computed(() => linkPairKeys(this._links().values()));

  /**
   * The warning badge each link currently carries.
   *
   * Derived rather than stored, so retyping a PLANT to a DC re-derives every affected badge without
   * anyone having to remember to.
   */
  readonly linkWarnings = computed<ReadonlyMap<Id, LinkWarning>>(() => {
    const nodes = this._nodes();
    const warnings = new Map<Id, LinkWarning>();
    for (const link of this._links().values()) {
      const source = nodes.get(link.sourceNodeId);
      const target = nodes.get(link.targetNodeId);
      if (!source || !target) {
        continue;
      }
      const warning = warningForLink(source.type, target.type);
      if (warning) {
        warnings.set(link.id, warning);
      }
    }
    return warnings;
  });

  /** Warnings on the current selection, listed in the property panel. */
  readonly selectedLinkWarnings = computed(() => {
    const warnings = this.linkWarnings();
    return this.selectedLinks()
      .map((link) => warnings.get(link.id))
      .filter(isPresent);
  });

  /** Per-product rows of the single selected node, or empty for any other selection. */
  readonly selectedNodeProducts = computed<readonly NodeProduct[]>(() => {
    const nodes = this.selectedNodes();
    if (nodes.length !== 1) {
      return [];
    }
    return this._nodeProducts().get(nodes[0].id) ?? [];
  });

  /** Catalogue products the selected node does not carry yet. */
  readonly availableProducts = computed<readonly Product[]>(() => {
    const taken = new Set(this.selectedNodeProducts().map((row) => row.productId));
    return this._products().filter((product) => !taken.has(product.id));
  });

  /** Nodes whose per-product rows are being fetched. Plain state: nothing renders from it. */
  private readonly loadingNodeProducts = new Set<Id>();
  private timeValidationTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // A duration edit reaches the server through the debounced bulk PATCH, so a completed save is
    // the moment the findings can have changed. Creates and deletes schedule their own refresh from
    // `run`, since they bypass the batch entirely.
    effect(() => {
      if (this.persistence.lastSavedAt() !== null) {
        this.scheduleTimeValidation();
      }
    });

    // Per-product rows are fetched only for a node the user is actually looking at: a 1,000-node
    // network (FR-04) would otherwise open with a thousand requests nobody asked for.
    effect(
      () => {
        const nodes = this.selectedNodes();
        if (nodes.length === 1) {
          void this.loadNodeProducts(nodes[0].id);
        }
      },
      // The fetch writes signals when it resolves, which is after this effect has returned; the flag
      // is here because Angular cannot tell the two apart.
      { allowSignalWrites: true },
    );
  }

  // ------------------------------------------------------------------------- loading

  /** `GET /networks/{id}` plus its nodes and links. */
  async load(networkId: Id): Promise<void> {
    this._networkId.set(networkId);
    this._state.set('loading');
    this._error.set(null);
    this._actionError.set(null);
    this.stack.clear();
    this.nodeRemap.clear();
    this.linkRemap.clear();
    this._selectedNodeIds.set(new Set());
    this._selectedLinkIds.set(new Set());
    this._nodeProducts.set(new Map());
    this._products.set([]);
    this._timeValidation.set(null);
    this._dismissedFindings.set(null);
    this._runs.set([]);
    this._runsError.set(null);
    this._discardPromptOpen.set(false);
    this.loadingNodeProducts.clear();
    this.persistence.configure(networkId);
    this.persistence.onNodesSaved = (nodes) => this.mergeNodes(nodes);
    this.persistence.onLinksSaved = (links) => this.mergeLinks(links);
    this.persistence.onFlushFailed = (failure) => this.handleImmutable(failure);

    try {
      // Through `core/api-nulls`, because `spring.jackson.default-property-inclusion=non_null`
      // means every nullable field is *absent* rather than null on the wire - and `undefined`
      // passes every `!== null` test the models were written against. Two readings in this store
      // turn on it: `needsInitialLayout` decides the auto-layout offer on
      // `posX === null`, and FR-30's undo decides on `caption === null` (`element-captions.ts`).
      const [network, nodes, links] = await Promise.all([
        firstValueFrom(this.api.get<Network>(`/networks/${networkId}`)),
        firstValueFrom(this.api.get<WireNode[]>(`/networks/${networkId}/nodes`)).then(
          normaliseNodes,
        ),
        firstValueFrom(this.api.get<WireLink[]>(`/networks/${networkId}/links`)).then(
          normaliseLinks,
        ),
      ]);
      this._network.set(network);
      this._nodes.set(new Map(nodes.map((node) => [node.id, node])));
      this._links.set(new Map(links.map((link) => [link.id, link])));
      // The auto-layout button exists for imported networks without coordinates. Offer it
      // only when nothing has been placed - a single manual position means the layout is the user's.
      this._needsInitialLayout.set(
        nodes.length > 0 && nodes.every((node) => node.posX === null || node.posY === null),
      );
      this._state.set('ready');
      // Both are courtesies rather than preconditions for editing, so neither is awaited and
      // neither failure can stop a network from opening.
      void this.loadProducts(networkId);
      void this.refreshTimeValidation();
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not open this network in the editor.'));
      this._state.set('error');
    }
  }

  dismissActionError(): void {
    this._actionError.set(null);
  }

  /**
   * Re-reads the network row alone - not its nodes, links, selection or undo stack.
   *
   * For the moments the `editable` flag changes under a live editing session without any edit being
   * made: a run accepted from the editor freezes the network at the 202 (FR-17), and a
   * cancelled or failed run releases it again. The server owns that answer - the editor cannot know
   * whether *other* runs still hold the network - so this asks rather than flipping the flag
   * locally. A failure keeps the current record: the guard on the next edit is the backstop, and it
   * routes into the fork prompt exactly as it always has.
   */
  async refreshNetworkRecord(): Promise<void> {
    const networkId = this._networkId();
    if (networkId === null) {
      return;
    }
    try {
      const network = await firstValueFrom(this.api.get<Network>(`/networks/${networkId}`));
      if (this._networkId() === networkId) {
        this._network.set(network);
      }
    } catch {
      // Keep what we have; NETWORK_IMMUTABLE on the next write is the honest fallback.
    }
  }

  /** `GET /networks/{id}/products` - the catalogue of the network's project. */
  private async loadProducts(networkId: Id): Promise<void> {
    try {
      this._products.set(
        await firstValueFrom(this.api.get<Product[]>(`/networks/${networkId}/products`)),
      );
    } catch {
      // An empty catalogue means the panel offers nothing to add, which is the honest outcome of
      // not knowing. Nothing else in the editor depends on it.
      this._products.set([]);
    }
  }

  /**
   * `POST /projects/{id}/products` - a new catalogue entry, without leaving the canvas (FR-01).
   *
   * The shortcut behind the property panel's "new product" field. It exists because the catalogue is
   * a precondition for demand and the panel is where the absence is felt: a node that needs demand,
   * and an empty picker above it, is a dead end otherwise, and the way out of it is two screens away.
   *
   * Three things it deliberately is not. It is **not routed through the command stack** - a product
   * outlives the network and is shared by every variant of the project, so undoing a
   * canvas edit must not delete a catalogue entry other networks may already be using. It writes
   * against the **project**, read off the open network, because that is what a product belongs to.
   * And it does **not** attach the product to anything; the caller adds it to the selected node
   * through {@link applyNodeProduct}, which is undoable, so the two halves keep their own semantics.
   *
   * `unitValue` opens at 0 and is corrected on the catalogue screen. Guessing a currency value from
   * a field labelled with a product name would be worse than leaving it plainly unset.
   *
   * @returns the created product, or null if the write failed - the message is in `actionError`.
   */
  async createProduct(name: string): Promise<Product | null> {
    const projectId = this._network()?.projectId;
    const trimmed = name.trim();
    if (!projectId || !trimmed || this._creatingProduct()) {
      return null;
    }
    this._creatingProduct.set(true);
    try {
      const product = await firstValueFrom(
        this.api.post<Product>(`/projects/${projectId}/products`, {
          name: trimmed,
          unitValue: 0,
        }),
      );
      this._products.update((list) =>
        [...list, product].sort((a, b) => a.name.localeCompare(b.name)),
      );
      return product;
    } catch (failure: unknown) {
      this._actionError.set(problemMessage(failure, 'Could not create the product.'));
      return null;
    } finally {
      this._creatingProduct.set(false);
    }
  }

  /** `GET /nodes/{id}/products`, once per node (`NODE_PRODUCT`). */
  private async loadNodeProducts(nodeId: Id): Promise<void> {
    if (this._nodeProducts().has(nodeId) || this.loadingNodeProducts.has(nodeId)) {
      return;
    }
    this.loadingNodeProducts.add(nodeId);
    try {
      const rows = await firstValueFrom(
        this.api.get<NodeProduct[]>(`/nodes/${nodeId}/products`),
      );
      this._nodeProducts.update((current) => new Map(current).set(nodeId, rows));
    } catch (failure: unknown) {
      this._actionError.set(
        problemMessage(failure, 'Could not load this node’s per-product parameters.'),
      );
    } finally {
      this.loadingNodeProducts.delete(nodeId);
    }
  }

  // ------------------------------------------------------------------------ gestures

  setLastUsedType(type: NodeType): void {
    this._lastUsedType.set(type);
  }

  toggleGridSnap(): void {
    this._gridSnap.update((on) => !on);
  }

  toggleBoxSelect(): void {
    this._boxSelect.update((on) => !on);
  }

  /** Rounds to the grid when snap is on; identity otherwise. */
  snap(value: number): number {
    return this._gridSnap() ? Math.round(value / this.gridSize) * this.gridSize : value;
  }

  /**
   * Palette drop, or double-click on empty canvas.
   *
   * The node is POSTed before it appears. That costs one round-trip, and buys a canvas that never
   * holds an element without a server id: no provisional ids to rewrite, no link drawn from a node
   * whose creation is still in flight, and a duplicate name surfacing as an error over empty canvas
   * rather than as a node that vanishes a moment later.
   *
   * @returns the created node, or null if the create failed or the network is frozen
   */
  async createNodeAt(type: NodeType, posX: number, posY: number): Promise<NetworkNode | null> {
    if (this.blockIfReadOnly()) {
      return null;
    }
    this._lastUsedType.set(type);
    const name = nextNodeName(type, this.nodeList().map((node) => node.name));
    const request = defaultNodeRequest(
      type,
      name,
      this.snap(posX),
      this.snap(posY),
      this.periodUnit(),
      this.units.defaultFor(UnitPreferencesService.NODE_CAPACITY, 'rate', this.periodUnit()),
    );
    const command = new AddNodeCommand(request);
    const ok = await this.run(command, 'Could not add the node.');
    if (!ok) {
      return null;
    }
    // AddNodeCommand selects what it created, so the panel is already pointing at it.
    return this.selectedNodes()[0] ?? null;
  }

  /** The edge-handle gesture completing over a valid target. */
  async connect(sourceNodeId: Id, targetNodeId: Id): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const verdict = this.verdictFor(sourceNodeId, targetNodeId);
    if (!verdict.ok) {
      // The gesture already refuses invalid targets; this is the belt-and-braces path for a
      // programmatic call.
      this._actionError.set(verdict.reason);
      return;
    }
    const nodes = this._nodes();
    const source = nodes.get(sourceNodeId);
    const target = nodes.get(targetNodeId);
    if (!source || !target) {
      return;
    }
    const command = new AddLinkCommand(
      defaultLinkRequest(
        sourceNodeId,
        targetNodeId,
        this.periodLength(),
        this.units.defaultFor(UnitPreferencesService.LINK_CAPACITY, 'rate', this.periodUnit()),
      ),
      source.name,
      target.name,
    );
    await this.run(command, 'Could not connect those nodes.');
  }

  /** Whether an arc may be drawn from `sourceNodeId` to `targetNodeId`. */
  verdictFor(sourceNodeId: Id, targetNodeId: Id): LinkVerdict {
    const nodes = this._nodes();
    const source = nodes.get(sourceNodeId);
    const target = nodes.get(targetNodeId);
    if (!source || !target) {
      return { ok: false, reason: LinkRefusal.SELF_LOOP };
    }
    return linkVerdict(
      { id: source.id, type: source.type },
      { id: target.id, type: target.type },
      this.pairKeys(),
    );
  }

  /** End of a drag: one command for the whole moved selection. */
  async moveNodes(moves: readonly NodeMove[], label?: string): Promise<void> {
    if (!moves.length || this.blockIfReadOnly()) {
      return;
    }
    await this.run(new MoveNodesCommand(moves, label), 'Could not move those nodes.');
  }

  /**
   * Apply one field across the current node selection (single and bulk edit).
   *
   * Only the field the user touched is sent, which is precisely PATCH's leave-alone semantics -
   * setting one capacity across twelve nodes must not overwrite the eleven attributes on which
   * those nodes differ.
   *
   * Change detection goes through {@link sameFieldValue} rather than `===`, because a `Duration` or
   * a `Rate` is an object: reference comparison would call every re-entered value a change and send
   * a PATCH for it.
   *
   * The `before` half goes through {@link previousAttributeValue}, which is where the caption's one
   * departure from the rest lives: a field that was null usually has no undo a PATCH can express, so
   * it is left out - except a caption, whose undo is the empty string this endpoint reads as *clear
   * it* (FR-30, `element-captions.ts`).
   */
  async applyNodeAttributes(attributes: NodeAttributes, fieldLabel: string): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const keys = Object.keys(attributes) as (keyof NodeAttributes)[];
    const edits: NodeEdit[] = [];
    for (const node of this.selectedNodes()) {
      const before: NodeAttributes = {};
      let changed = false;
      for (const key of keys) {
        const next = attributes[key];
        const current = node[key];
        if (sameFieldValue(current, next)) {
          continue;
        }
        changed = true;
        const previous = previousAttributeValue(key, current);
        if (previous !== undefined) {
          Object.assign(before, { [key]: previous });
        }
      }
      if (changed) {
        edits.push({ nodeId: node.id, before, after: attributes });
      }
    }
    if (!edits.length) {
      return;
    }
    await this.run(new EditNodesCommand(edits, fieldLabel), 'Could not apply that edit.');
  }

  /**
   * Apply one field across the current link selection.
   *
   * `applyToEach` is how a unit change reaches a multi-selection: the panel cannot state one new
   * value for links that each hold their own number, so it hands over a function that restates each
   * link's own value in the chosen unit, and the whole selection still lands as one command.
   */
  async applyLinkAttributes(
    attributes: LinkAttributes | ((link: NetworkLink) => LinkAttributes),
    fieldLabel: string,
  ): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const edits: LinkEdit[] = [];
    for (const link of this.selectedLinks()) {
      const after = typeof attributes === 'function' ? attributes(link) : attributes;
      const before: LinkAttributes = {};
      let changed = false;
      for (const key of Object.keys(after) as (keyof LinkAttributes)[]) {
        const current = link[key];
        if (sameFieldValue(current, after[key])) {
          continue;
        }
        changed = true;
        const previous = previousAttributeValue(key, current);
        if (previous !== undefined) {
          Object.assign(before, { [key]: previous });
        }
      }
      if (changed) {
        edits.push({ linkId: link.id, before, after });
      }
    }
    if (!edits.length) {
      return;
    }
    await this.run(new EditLinksCommand(edits, fieldLabel), 'Could not apply that edit.');
  }

  /** The node counterpart of {@link applyLinkAttributes}'s per-element form. */
  async applyNodeAttributesEach(
    attributesFor: (node: NetworkNode) => NodeAttributes,
    fieldLabel: string,
  ): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const edits: NodeEdit[] = [];
    for (const node of this.selectedNodes()) {
      const after = attributesFor(node);
      const before: NodeAttributes = {};
      let changed = false;
      for (const key of Object.keys(after) as (keyof NodeAttributes)[]) {
        const current = node[key];
        if (sameFieldValue(current, after[key])) {
          continue;
        }
        changed = true;
        const previous = previousAttributeValue(key, current);
        if (previous !== undefined) {
          Object.assign(before, { [key]: previous });
        }
      }
      if (changed) {
        edits.push({ nodeId: node.id, before, after });
      }
    }
    if (!edits.length) {
      return;
    }
    await this.run(new EditNodesCommand(edits, fieldLabel), 'Could not apply that edit.');
  }

  /**
   * Clear a nullable node field on the single selected node.
   *
   * Routed through `PUT` because a PATCH cannot express it: an omitted field there means "leave
   * alone", so there is no encoding of "make this null" (`NodeController`).
   */
  async clearNodeField(
    field: 'capacity' | 'region' | 'lat' | 'lng',
    fieldLabel: string,
  ): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const node = this.selectedNodes()[0];
    if (!node || this.selectedNodes().length !== 1) {
      return;
    }
    const before = toNodeRequest(node);
    await this.run(
      new ReplaceNodeCommand(node.id, before, cleared(before, field), fieldLabel),
      `Could not clear ${fieldLabel}.`,
    );
  }

  /** Clear the single selected link's capacity back to unconstrained. */
  async clearLinkCapacity(): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const link = this.selectedLinks()[0];
    if (!link || this.selectedLinks().length !== 1) {
      return;
    }
    // Every field, because a PUT clears what it omits - including the caption pair, which this
    // gesture is not editing and must not take with it (FR-30). `toNodeRequest` states the same
    // rule for the node side, where it has one more field to get wrong.
    const before: LinkAttributesRequest = {
      leadTime: link.leadTime,
      capacity: link.capacity,
      unitCost: link.unitCost,
      failureProb: link.failureProb,
      caption: link.caption,
      captionVisible: link.captionVisible,
    };
    // The unit survives the clearing: `{value: null}` is an unconstrained capacity *stated in a
    // unit*, so giving it a number again does not need one invented for it.
    const after: LinkAttributesRequest = {
      ...before,
      capacity: { value: null, timeUnit: link.capacity.timeUnit },
    };
    await this.run(
      new ReplaceLinkCommand(link.id, before, after, 'capacity'),
      'Could not clear the capacity.',
    );
  }

  // -------------------------------------------------------------- per-product rows (5.1)

  /**
   * Write one node–product row: create it, or change one of its four figures.
   *
   * Routed through the command stack like every other edit, so demand entered in the wrong unit is
   * one Ctrl-Z away. Immediate rather than batched - there is no bulk endpoint for these rows, and
   * no gesture that produces them in bulk.
   */
  async applyNodeProduct(
    productId: Id,
    request: NodeProductRequest,
    fieldLabel: string,
  ): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const node = this.selectedNodes()[0];
    if (!node || this.selectedNodes().length !== 1) {
      return;
    }
    const existing = this.getNodeProduct(node.id, productId);
    const productName =
      existing?.productName ??
      this._products().find((product) => product.id === productId)?.name ??
      `#${productId}`;
    await this.run(
      new UpsertNodeProductCommand(
        node.id,
        productId,
        existing ? toNodeProductRequest(existing) : null,
        request,
        productName,
        fieldLabel,
      ),
      'Could not save the per-product parameters.',
    );
  }

  /** Take a product off the selected node. Undo puts the row back with its figures intact. */
  async removeNodeProduct(productId: Id): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const node = this.selectedNodes()[0];
    const existing = node ? this.getNodeProduct(node.id, productId) : undefined;
    if (!node || !existing) {
      return;
    }
    await this.run(
      new RemoveNodeProductCommand(
        node.id,
        productId,
        toNodeProductRequest(existing),
        existing.productName,
      ),
      'Could not remove that product from the node.',
    );
  }

  // ------------------------------------------------------- the network clock (5.5.3, 5.5.4)

  /**
   * `PUT /networks/{id}/time-base` - period length, horizon and rounding policy together.
   *
   * A structural edit in every sense that matters: results are stated in periods, so redefining the
   * period turns "TTR = 14" from fourteen days into fourteen hours without altering a stored number.
   * The server refuses it on a network with runs, and {@link handleImmutable} answers that with the
   * fork prompt - the same path a blocked node edit takes.
   *
   * Deliberately **not** an undoable command. The stack covers add/move/connect/edit/delete on
   * canvas elements; a time base is a property of the network, is set from a dialog the
   * user confirms, and re-deriving every warning behind a Ctrl-Z would be a worse surprise than
   * opening the dialog again.
   *
   * @returns true when the new clock was saved
   */
  async applyTimeBase(request: TimeBaseRequest): Promise<boolean> {
    const networkId = this._networkId();
    if (networkId === null || this.blockIfReadOnly()) {
      return false;
    }
    this._timeBaseBusy.set(true);
    try {
      // Pending edits first: they were entered against the old clock, and a bulk PATCH landing
      // after the time base changed would be validated against a period the user never saw.
      await this.persistence.flush();
      const response = await firstValueFrom(
        this.api.put<TimeBaseResponse>(`/networks/${networkId}/time-base`, request),
      );
      this._network.set(response.network);
      // The report arrives with the save that caused it, so the banner needs no second round trip.
      this.setTimeValidation(response.validation);
      this._actionError.set(null);
      // Capacities and demands are per-period quantities, so a new period re-scales every number
      // the metric engine sees. The ratios most of the suite is built from survive it,
      // but "most" is not a reason to leave a stale suite on screen.
      this._topologyRevision.update((revision) => revision + 1);
      return true;
    } catch (failure: unknown) {
      this.reportFailure(failure, 'Could not change the time base.');
      return false;
    } finally {
      this._timeBaseBusy.set(false);
    }
  }

  /**
   * `GET /networks/{id}/time-validation` - the resolution findings and the suggested period.
   *
   * The suggestion is the server's: it is computed from every declared duration in the network, not
   * only the ones on screen, so asking the client to derive it would mean shipping the whole model
   * to the browser to answer one question.
   */
  async refreshTimeValidation(): Promise<TimeValidationReport | null> {
    const networkId = this._networkId();
    if (networkId === null) {
      return null;
    }
    this._timeValidationBusy.set(true);
    try {
      const report = await firstValueFrom(
        this.api.get<TimeValidationReport>(`/networks/${networkId}/time-validation`),
      );
      this.setTimeValidation(report);
      return report;
    } catch {
      // Never a banner of its own: a failed check is not a finding, and the editor stays usable
      // without one. The next edit tries again.
      return null;
    } finally {
      this._timeValidationBusy.set(false);
    }
  }

  /** Hide the resolution banner until the findings change. */
  dismissTimeWarnings(): void {
    this._dismissedFindings.set(fingerprint(this.timeFindings()));
  }

  /** Select the element a banner entry names, so clicking it reveals it on the canvas. */
  selectFinding(elementType: TimeElementType, elementId: Id): void {
    if (elementType === TimeElementType.NODE && this._nodes().has(elementId)) {
      this.select([elementId], []);
      return;
    }
    if (elementType === TimeElementType.LINK && this._links().has(elementId)) {
      this.select([], [elementId]);
    }
    // A DISRUPTION_EVENT finding has nothing on this canvas to select; the banner does not offer it.
  }

  private setTimeValidation(report: TimeValidationReport): void {
    const previous = fingerprint(this.timeFindings());
    this._timeValidation.set(report);
    // A dismissal covers the findings it was aimed at. Identical findings stay dismissed; a report
    // that says something new raises the banner again.
    if (this._dismissedFindings() !== null && previous !== fingerprint(report.findings)) {
      this._dismissedFindings.set(null);
    }
  }

  /**
   * Re-ask after an edit, coalescing bursts.
   *
   * Dragging a slider of a lead time through five values must not cost five round trips, and the
   * findings only matter once the value settles.
   */
  private scheduleTimeValidation(): void {
    if (this.timeValidationTimer !== null) {
      clearTimeout(this.timeValidationTimer);
    }
    this.timeValidationTimer = setTimeout(() => {
      this.timeValidationTimer = null;
      void this.refreshTimeValidation();
    }, TIME_VALIDATION_DEBOUNCE_MS);
  }

  /**
   * What a delete of the current selection would take with it.
   *
   * Links and node–product rows are enumerated for real. Disruption events targeting these nodes
   * are **not**: `disruption_event` carries the `ix_event_target` index for exactly this question,
   * but the `/scenarios` endpoints are not built yet, so there is nothing to ask. Saying so is
   * better than a confirm that quietly under-reports.
   */
  async deletionImpact(): Promise<DeletionImpact> {
    const nodes = this.selectedNodes();
    const links = this.selectedLinks();
    const cascading = new Map<Id, NetworkLink>();
    for (const link of links) {
      cascading.set(link.id, link);
    }
    for (const node of nodes) {
      for (const link of this.incidentLinks(node.id)) {
        cascading.set(link.id, link);
      }
    }

    const details: string[] = [];
    if (nodes.length) {
      details.push(`Nodes: ${nodes.map((node) => node.name).join(', ')}`);
    }
    const linkList = [...cascading.values()];
    if (linkList.length) {
      const shown = linkList.slice(0, 10).map((link) => this.describeLink(link));
      const suffix = linkList.length > shown.length ? `, and ${linkList.length - shown.length} more` : '';
      details.push(`${linkList.length} link(s) will be deleted: ${shown.join('; ')}${suffix}`);
    }
    if (nodes.length) {
      const productRows = await this.countNodeProducts(nodes.map((node) => node.id));
      details.push(
        productRows === null
          ? 'Per-product rows on these nodes could not be counted; they will be deleted with the nodes.'
          : `${productRows} per-product row(s) on these nodes will be deleted.`,
      );
      details.push(
        'Disruption events targeting these nodes cannot be listed: the scenario API is not built yet.',
      );
    }
    return { nodeCount: nodes.length, linkCount: linkList.length, details };
  }

  /** Delete the current selection. Call {@link deletionImpact} and confirm first. */
  async deleteSelection(): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const nodeIds = [...this._selectedNodeIds()];
    const linkIds = [...this._selectedLinkIds()];
    if (!nodeIds.length && !linkIds.length) {
      return;
    }
    await this.run(
      new DeleteElementsCommand(nodeIds, linkIds),
      'Could not delete the selection.',
    );
  }

  /** Result of the auto-layout button, as one undoable move of every node. */
  async applyLayout(positions: ReadonlyMap<Id, { posX: number; posY: number }>): Promise<void> {
    if (this.blockIfReadOnly()) {
      return;
    }
    const nodes = this._nodes();
    const moves: NodeMove[] = [];
    for (const [nodeId, to] of positions) {
      const node = nodes.get(nodeId);
      if (!node) {
        continue;
      }
      moves.push({
        nodeId,
        // A node that had no coordinates has nothing to go back to: a PATCH cannot restore null
        // (only PUT can), so undoing the first auto-layout of an imported network leaves those
        // nodes where the layout put them. Everything with a real previous position is restored.
        from: { posX: node.posX ?? to.posX, posY: node.posY ?? to.posY },
        to,
      });
    }
    if (!moves.length) {
      return;
    }
    this._needsInitialLayout.set(false);
    await this.run(new MoveNodesCommand(moves, 'Auto-layout'), 'Could not save the layout.');
  }

  /** True once every node has coordinates - used to warn before an auto-layout overwrites them. */
  readonly hasManualPositions = computed(() =>
    this.nodeList().some((node) => node.posX !== null && node.posY !== null),
  );

  // ------------------------------------------------------------------- undo and redo

  async undo(): Promise<void> {
    if (!this.stack.canUndo() || this.stack.busy()) {
      return;
    }
    try {
      await this.stack.undo(this);
      // Undoing a lead time is as much a change to the findings as setting one was.
      this.scheduleTimeValidation();
      // Unconditional, unlike `run`: the stack does not say which command it just reversed, and
      // one wasted recompute after undoing a drag is cheaper than the machinery to avoid it.
      this._topologyRevision.update((revision) => revision + 1);
    } catch (failure: unknown) {
      this.reportFailure(failure, 'Could not undo that.');
    }
  }

  async redo(): Promise<void> {
    if (!this.stack.canRedo() || this.stack.busy()) {
      return;
    }
    try {
      await this.stack.redo(this);
      this.scheduleTimeValidation();
      this._topologyRevision.update((revision) => revision + 1);
    } catch (failure: unknown) {
      this.reportFailure(failure, 'Could not redo that.');
    }
  }

  // ------------------------------------------------------ network immutability (5.2)

  openForkPrompt(): void {
    this._forkPromptOpen.set(true);
  }

  closeForkPrompt(): void {
    this._forkPromptOpen.set(false);
  }

  /**
   * `POST /networks/{id}/clone` - the fork.
   *
   * The blocked edit is deliberately *not* replayed onto the variant. Reapplying an edit across a
   * fork is the kind of helpfulness that silently produces a configuration nobody chose, and the
   * freeze exists precisely so results stay interpretable beside the structure that produced them.
   *
   * The request itself is `core/NetworkCloneService`, shared with the project dashboard's
   * *Duplicate network* (FR-26). What stays here is what only the editor has to do around it -
   * dropping the pending canvas writes and the undo stack, and closing the prompt - because the
   * copy the dashboard makes has neither.
   */
  async fork(request: ForkRequest): Promise<Network | null> {
    const networkId = this._networkId();
    if (networkId === null) {
      return null;
    }
    this._forking.set(true);
    try {
      const variant = await firstValueFrom(this.clones.clone(networkId, request));
      this.persistence.discardPending();
      this.stack.clear();
      this._forkPromptOpen.set(false);
      return variant.network;
    } catch (failure: unknown) {
      this._actionError.set(problemMessage(failure, 'Could not create the configuration variant.'));
      return null;
    } finally {
      this._forking.set(false);
    }
  }

  // ------------------------------------------------ discarding the runs (FR-20)

  /**
   * `GET /networks/{id}/runs` - the rows the freeze actually consists of, and the run
   * history of FR-21.
   *
   * Still not fetched on load, and now fetched by three gestures rather than two: the researcher
   * asking to unlock, the fork prompt offering its third choice, and - since FR-21 - the run panel
   * opening, which is where the list is *rendered* rather than counted. The
   * panel also re-reads it when a run settles and after every discard, which is the whole of that
   * section's refresh rule; nothing polls it.
   *
   * **The failure is reported through {@link runsError} rather than through the canvas banner**,
   * because the panel that asks for it most often has somewhere of its own to say so. The discard
   * path still raises the banner - see {@link openDiscardPrompt}, which is the caller that has
   * nowhere else to put it.
   *
   * @returns true if the list is current; false if the read failed and the caller should not act on it
   */
  async loadNetworkRuns(): Promise<boolean> {
    const networkId = this._networkId();
    if (networkId === null) {
      return false;
    }
    this._loadingRuns.set(true);
    try {
      const runs = await firstValueFrom(this.api.get<WireRun[]>(`/networks/${networkId}/runs`));
      if (this._networkId() !== networkId) {
        return false;
      }
      // The API omits null fields, so `importedAt` is absent on every run this installation
      // computed - and `restoredRuns` in `core/run-discard.ts` selects on `!== null`. Without this
      // the FR-20 confirmation warns that a restored archive result is about to be destroyed on
      // every discard, which is a false statement attached to an irreversible action.
      // FR-21's tiles carry the same mark from the same field, so the same normalisation serves it.
      this._runs.set(normaliseRuns(runs));
      this._runsError.set(null);
      return true;
    } catch (failure: unknown) {
      this._runsError.set(problemMessage(failure, 'Could not read this network’s simulation runs.'));
      return false;
    } finally {
      this._loadingRuns.set(false);
    }
  }

  /**
   * Opens the typed discard confirmation - the "Unlock…" banner action and the fork prompt's third
   * choice both land here (FR-20).
   *
   * **The runs are re-read first, every time.** The dialog's whole job is to state what is about to
   * be destroyed, and a count taken when the editor opened would be wrong the moment a run
   * completed beside it. It also decides whether the dialog opens at all: while a run is `QUEUED` or
   * `RUNNING` the server refuses the request whole and deletes nothing, so the honest answer is the
   * sentence {@link activeRunsBlocker} writes rather than a confirmation that would 409 after the
   * user had typed the phrase.
   */
  async openDiscardPrompt(): Promise<void> {
    this._actionError.set(null);
    if (!(await this.loadNetworkRuns())) {
      // This caller has nowhere else to say it: the gesture was "unlock", and it produced neither a
      // dialog nor an explanation without this line (FR-21 moved the message onto `runsError`).
      this._actionError.set(this._runsError() ?? 'Could not read this network’s simulation runs.');
      return;
    }
    const blocker = activeRunsBlocker(this._runs());
    if (blocker !== null) {
      this._actionError.set(blocker);
      return;
    }
    this._forkPromptOpen.set(false);
    this._discardPromptOpen.set(true);
  }

  closeDiscardPrompt(): void {
    this._discardPromptOpen.set(false);
  }

  /**
   * `DELETE /networks/{id}/runs` - discard every run of this network and edit in place (FR-20).
   *
   * Then re-reads the network row, exactly as a submitted run does at the 202
   * ({@link refreshNetworkRecord}): the banner clears because the server says the network is
   * editable, never because this store decided it was. The distinction is the same one FR-17
   * established and it matters more here - whether the freeze is really gone depends on rows this
   * client does not own, and a locally flipped flag would let the next edit fail with a banner that
   * had already promised otherwise.
   *
   * A `RUN_ACTIVE` 409 is possible even after the gate in {@link openDiscardPrompt}: a run can be
   * submitted from another tab while the dialog is open. Nothing was deleted in that case, and the
   * problem's own sentence says so - which is why it is surfaced verbatim rather than reworded.
   *
   * @returns true when the runs are gone
   */
  async discardRuns(): Promise<boolean> {
    const networkId = this._networkId();
    if (networkId === null || this._discarding()) {
      return false;
    }
    this._discarding.set(true);
    try {
      await firstValueFrom(this.api.delete<void>(`/networks/${networkId}/runs`));
      this._runs.set([]);
      this._discardPromptOpen.set(false);
      this._forkPromptOpen.set(false);
      this._actionError.set(null);
      await this.refreshNetworkRecord();
      return true;
    } catch (failure: unknown) {
      this._discardPromptOpen.set(false);
      this._actionError.set(problemMessage(failure, 'Could not discard this network’s runs.'));
      // The count on screen was taken before the failure; re-read it so a second attempt is not
      // argued from a list the server has just contradicted.
      void this.loadNetworkRuns();
      return false;
    } finally {
      this._discarding.set(false);
    }
  }

  // -------------------------------------------------------------- CommandContext impl

  get networkId(): Id {
    const id = this._networkId();
    if (id === null) {
      throw new Error('The editor store has no network loaded.');
    }
    return id;
  }

  node(id: Id): Id {
    return resolveRemapped(this.nodeRemap, id);
  }

  link(id: Id): Id {
    return resolveRemapped(this.linkRemap, id);
  }

  remapNode(oldId: Id, newId: Id): void {
    this.nodeRemap.set(oldId, newId);
  }

  remapLink(oldId: Id, newId: Id): void {
    this.linkRemap.set(oldId, newId);
  }

  getNode(id: Id): NetworkNode | undefined {
    return this._nodes().get(id);
  }

  getLink(id: Id): NetworkLink | undefined {
    return this._links().get(id);
  }

  incidentLinks(nodeId: Id): readonly NetworkLink[] {
    return this.linkList().filter(
      (link) => link.sourceNodeId === nodeId || link.targetNodeId === nodeId,
    );
  }

  getNodeProduct(nodeId: Id, productId: Id): NodeProduct | undefined {
    return this._nodeProducts()
      .get(nodeId)
      ?.find((row) => row.productId === productId);
  }

  upsertNode(node: NetworkNode): void {
    this._nodes.update((nodes) => new Map(nodes).set(node.id, node));
  }

  upsertLink(link: NetworkLink): void {
    this._links.update((links) => new Map(links).set(link.id, link));
  }

  setNodeProduct(row: NodeProduct): void {
    this._nodeProducts.update((current) => {
      const rows = current.get(row.nodeId) ?? [];
      const known = rows.some((existing) => existing.productId === row.productId);
      const next = known
        ? rows.map((existing) => (existing.productId === row.productId ? row : existing))
        : [...rows, row];
      return new Map(current).set(row.nodeId, next);
    });
  }

  dropNodeProduct(nodeId: Id, productId: Id): void {
    this._nodeProducts.update((current) => {
      const rows = current.get(nodeId);
      if (!rows) {
        return current;
      }
      return new Map(current).set(
        nodeId,
        rows.filter((row) => row.productId !== productId),
      );
    });
  }

  removeNode(id: Id): void {
    this._nodes.update((nodes) => {
      const next = new Map(nodes);
      next.delete(id);
      return next;
    });
    // The node's per-product rows cascade server-side; drop the cached copy with them, or an undo
    // that re-creates the node under a new id would leave the old rows stranded in the map.
    this._nodeProducts.update((current) => {
      if (!current.has(id)) {
        return current;
      }
      const next = new Map(current);
      next.delete(id);
      return next;
    });
    // Links cascade server-side; drop them here too so the canvas never shows a dangling arc.
    this._links.update((links) => {
      const next = new Map(links);
      for (const [linkId, link] of links) {
        if (link.sourceNodeId === id || link.targetNodeId === id) {
          next.delete(linkId);
        }
      }
      return next;
    });
    this._selectedNodeIds.update((selected) => without(selected, id));
  }

  removeLink(id: Id): void {
    this._links.update((links) => {
      const next = new Map(links);
      next.delete(id);
      return next;
    });
    this._selectedLinkIds.update((selected) => without(selected, id));
  }

  /**
   * Replace the selection.
   *
   * The equality check is load-bearing, not an optimisation. Selection flows both ways - the canvas
   * reports what the user picked, and an effect pushes the store's selection back into Cytoscape -
   * so setting a *new* `Set` with the same contents on every round would leave the two bouncing off
   * each other forever.
   */
  select(nodeIds: readonly Id[], linkIds: readonly Id[]): void {
    if (!sameIds(this._selectedNodeIds(), nodeIds)) {
      this._selectedNodeIds.set(new Set(nodeIds));
    }
    if (!sameIds(this._selectedLinkIds(), linkIds)) {
      this._selectedLinkIds.set(new Set(linkIds));
    }
  }

  // Every one of these four answers with a node or a link, so every one goes through
  // `core/api-nulls` - the same rule `load` follows, applied where a *single* element arrives.
  async createNode(request: NodeRequest): Promise<NetworkNode> {
    await this.persistence.flush();
    return normaliseNode(
      await firstValueFrom(this.api.post<WireNode>(`/networks/${this.networkId}/nodes`, request)),
    );
  }

  async deleteNode(id: Id): Promise<void> {
    await this.persistence.flush();
    await firstValueFrom(this.api.delete<void>(`/nodes/${id}`));
  }

  async createLink(request: LinkRequest): Promise<NetworkLink> {
    await this.persistence.flush();
    return normaliseLink(
      await firstValueFrom(this.api.post<WireLink>(`/networks/${this.networkId}/links`, request)),
    );
  }

  async deleteLink(id: Id): Promise<void> {
    await this.persistence.flush();
    await firstValueFrom(this.api.delete<void>(`/links/${id}`));
  }

  async replaceNode(id: Id, request: NodeRequest): Promise<NetworkNode> {
    await this.persistence.flush();
    return normaliseNode(await firstValueFrom(this.api.put<WireNode>(`/nodes/${id}`, request)));
  }

  async replaceLink(id: Id, request: LinkAttributesRequest): Promise<NetworkLink> {
    await this.persistence.flush();
    return normaliseLink(await firstValueFrom(this.api.put<WireLink>(`/links/${id}`, request)));
  }

  async upsertNodeProduct(
    nodeId: Id,
    productId: Id,
    request: NodeProductRequest,
  ): Promise<NodeProduct> {
    await this.persistence.flush();
    return firstValueFrom(
      this.api.put<NodeProduct>(`/nodes/${nodeId}/products/${productId}`, request),
    );
  }

  async deleteNodeProduct(nodeId: Id, productId: Id): Promise<void> {
    await this.persistence.flush();
    await firstValueFrom(this.api.delete<void>(`/nodes/${nodeId}/products/${productId}`));
  }

  queuePosition(id: Id, posX: number, posY: number): void {
    this.persistence.queuePosition(id, posX, posY);
  }

  queueNodePatch(id: Id, patch: NodeAttributes): void {
    this.persistence.queueNodePatch(id, patch);
  }

  queueLinkPatch(id: Id, patch: LinkAttributes): void {
    this.persistence.queueLinkPatch(id, patch);
  }

  flush(): Promise<void> {
    return this.persistence.flush();
  }

  // ------------------------------------------------------------------------ internals

  private async run(command: EditorCommand, fallbackMessage: string): Promise<boolean> {
    try {
      await this.stack.push(this, command);
      this._actionError.set(null);
      // Creates, deletes and the immediate PUT paths bypass the debounced batch, so the
      // save-triggered refresh would never see them.
      this.scheduleTimeValidation();
      if (!(command instanceof MoveNodesCommand)) {
        this._topologyRevision.update((revision) => revision + 1);
      }
      return true;
    } catch (failure: unknown) {
      this.reportFailure(failure, fallbackMessage);
      return false;
    }
  }

  private reportFailure(failure: unknown, fallbackMessage: string): void {
    if (this.handleImmutable(failure)) {
      return;
    }
    this._actionError.set(problemMessage(failure, fallbackMessage));
  }

  /** Answers a 409 `NETWORK_IMMUTABLE` with the fork prompt rather than a retry. */
  private handleImmutable(failure: unknown): boolean {
    if (problemCode(failure) !== ProblemCode.NETWORK_IMMUTABLE) {
      return false;
    }
    const network = this._network();
    if (network) {
      this._network.set({ ...network, editable: false });
    }
    this.persistence.discardPending();
    this._actionError.set(
      'A simulation run has frozen this network, so it can no longer be edited. ' +
        'Fork a configuration variant to keep the result and carry on, or discard the runs to ' +
        'edit this network in place (FR-20).',
    );
    this._forkPromptOpen.set(true);
    return true;
  }

  /** Opens the fork prompt instead of acting when the network is already known to be frozen. */
  private blockIfReadOnly(): boolean {
    if (!this.readOnly()) {
      return false;
    }
    this._forkPromptOpen.set(true);
    return true;
  }

  private mergeNodes(nodes: readonly NetworkNode[]): void {
    if (!nodes.length) {
      return;
    }
    this._nodes.update((current) => {
      const next = new Map(current);
      for (const node of nodes) {
        // Only refresh what is still on the canvas: a node deleted while its patch was in flight
        // must not come back as a ghost.
        if (next.has(node.id)) {
          next.set(node.id, node);
        }
      }
      return next;
    });
  }

  private mergeLinks(links: readonly NetworkLink[]): void {
    if (!links.length) {
      return;
    }
    this._links.update((current) => {
      const next = new Map(current);
      for (const link of links) {
        if (next.has(link.id)) {
          next.set(link.id, link);
        }
      }
      return next;
    });
  }

  private describeLink(link: NetworkLink): string {
    const nodes = this._nodes();
    const source = nodes.get(link.sourceNodeId)?.name ?? `#${link.sourceNodeId}`;
    const target = nodes.get(link.targetNodeId)?.name ?? `#${link.targetNodeId}`;
    return `${source} → ${target}`;
  }

  /** The route component is leaving: nothing pending should outlive the editing session. */
  ngOnDestroy(): void {
    if (this.timeValidationTimer !== null) {
      clearTimeout(this.timeValidationTimer);
      this.timeValidationTimer = null;
    }
  }

  /** Total node–product rows across the selection, or null if any lookup failed. */
  private async countNodeProducts(nodeIds: readonly Id[]): Promise<number | null> {
    try {
      const responses = await Promise.all(
        nodeIds.map((id) => firstValueFrom(this.api.get<NodeProduct[]>(`/nodes/${id}/products`))),
      );
      return responses.reduce((total, rows) => total + rows.length, 0);
    } catch {
      // A count is a courtesy, not a precondition - never block the confirm on it.
      return null;
    }
  }
}

function isPresent<T>(value: T | undefined): value is T {
  return value !== undefined;
}

/**
 * How long a burst of edits is allowed to settle before the resolution checks are re-run.
 *
 * Comfortably longer than a keystroke and shorter than the two-second save window, so the banner
 * follows the network rather than the typing.
 */
const TIME_VALIDATION_DEBOUNCE_MS = 500;

/**
 * A stable identity for a set of findings, so a dismissal can be scoped to what was dismissed.
 *
 * Deliberately excludes the message - the sentence is prose the backend may reword, while the code,
 * the element and the converted value are the contract.
 */
function fingerprint(findings: readonly TimeFinding[]): string {
  return findings
    .map(
      (finding) =>
        `${finding.code}|${finding.elementType}|${finding.elementId}|${finding.field}|` +
        `${finding.convertedPeriods ?? ''}|${finding.declaredValue?.value ?? ''}` +
        `${finding.declaredValue?.unit ?? ''}`,
    )
    .join(';');
}

/**
 * A copy of `request` with one nullable field unset.
 *
 * Written out per field rather than with a computed key so the compiler still checks that the field
 * really is nullable - `NodeRequest`'s cost and probability fields are not, and clearing one of
 * those would be a 400 the type system can catch here instead.
 *
 * Clearing a capacity keeps its unit: an unconstrained rate is still a well-formed pair.
 */
function cleared(
  request: NodeRequest,
  field: 'capacity' | 'region' | 'lat' | 'lng',
): NodeRequest {
  switch (field) {
    case 'capacity':
      return {
        ...request,
        capacity: { value: null, timeUnit: request.capacity?.timeUnit ?? 'DAY' },
      };
    case 'region':
      return { ...request, region: null };
    case 'lat':
      return { ...request, lat: null };
    case 'lng':
      return { ...request, lng: null };
  }
}

/** Set equality, ignoring order - the selection is a set even though the canvas reports an array. */
function sameIds(current: ReadonlySet<Id>, next: readonly Id[]): boolean {
  if (current.size !== next.length) {
    return false;
  }
  return next.every((id) => current.has(id));
}

function without(selected: ReadonlySet<Id>, id: Id): ReadonlySet<Id> {
  if (!selected.has(id)) {
    return selected;
  }
  const next = new Set(selected);
  next.delete(id);
  return next;
}
