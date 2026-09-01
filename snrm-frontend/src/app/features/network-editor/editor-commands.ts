import { signal } from '@angular/core';

import {
  Duration,
  Id,
  LinkAttributesRequest,
  LinkRequest,
  NetworkLink,
  NetworkNode,
  NodeProduct,
  NodeProductRequest,
  NodeRequest,
  NodeType,
  Rate,
} from '../../core/models';

/**
 * The undo/redo command stack - "a client-side command stack covering
 * add/move/connect/edit/delete".
 *
 * Every mutation in the editor goes through {@link CommandStack.push}. There is no other write path
 * into the store, which is what makes undo total rather than best-effort.
 *
 * ## The id problem, and the remap table
 *
 * Undoing a delete cannot restore the row that was deleted: the API has no soft delete, so the undo
 * re-creates it and MySQL hands back a *new* id. Every older stack entry naming the old id would be
 * stranded. So commands never dereference an id they captured at construction - they resolve it
 * through {@link CommandContext.node} / {@link CommandContext.link} at execution time, and a
 * re-create records `old -> new` in the remap. The lookup follows the chain transitively, so a node
 * deleted and undone three times still resolves from its original id.
 */

/** What a command may do to the editor. The store implements this; specs implement a fake. */
export interface CommandContext {
  readonly networkId: Id;

  // -- id resolution -----------------------------------------------------------------

  /** The id a node originally created as `id` currently has. Follows the remap chain. */
  node(id: Id): Id;
  /** The id a link originally created as `id` currently has. Follows the remap chain. */
  link(id: Id): Id;
  /** Record that a re-created node took a new id. */
  remapNode(oldId: Id, newId: Id): void;
  /** Record that a re-created link took a new id. */
  remapLink(oldId: Id, newId: Id): void;

  // -- local state -------------------------------------------------------------------

  getNode(id: Id): NetworkNode | undefined;
  getLink(id: Id): NetworkLink | undefined;
  /** Every link with `id` at either end, resolved against current state. */
  incidentLinks(nodeId: Id): readonly NetworkLink[];
  getNodeProduct(nodeId: Id, productId: Id): NodeProduct | undefined;
  upsertNode(node: NetworkNode): void;
  upsertLink(link: NetworkLink): void;
  setNodeProduct(row: NodeProduct): void;
  removeNode(id: Id): void;
  removeLink(id: Id): void;
  dropNodeProduct(nodeId: Id, productId: Id): void;
  select(nodeIds: readonly Id[], linkIds: readonly Id[]): void;

  // -- immediate writes (no bulk endpoint exists for these) --------------------------

  createNode(request: NodeRequest): Promise<NetworkNode>;
  deleteNode(id: Id): Promise<void>;
  createLink(request: LinkRequest): Promise<NetworkLink>;
  deleteLink(id: Id): Promise<void>;
  /** `PUT /nodes/{id}` - the only call that can clear a nullable node field. */
  replaceNode(id: Id, request: NodeRequest): Promise<NetworkNode>;
  /** `PUT /links/{id}` - the only call that can clear a link's capacity. */
  replaceLink(id: Id, request: LinkAttributesRequest): Promise<NetworkLink>;
  /** `PUT /nodes/{id}/products/{productId}` - an upsert, so it both creates and replaces. */
  upsertNodeProduct(
    nodeId: Id,
    productId: Id,
    request: NodeProductRequest,
  ): Promise<NodeProduct>;
  deleteNodeProduct(nodeId: Id, productId: Id): Promise<void>;

  // -- batched writes (debounced to the bulk endpoints) ---------------------

  queuePosition(id: Id, posX: number, posY: number): void;
  queueNodePatch(id: Id, patch: NodeAttributes): void;
  queueLinkPatch(id: Id, patch: LinkAttributes): void;
  /** Send everything pending now. Awaited before any immediate write, to keep server order. */
  flush(): Promise<void>;
}

/**
 * Node fields the property panel can change through a bulk PATCH.
 *
 * Never `null` at the top level - an omitted field is what PATCH reads as "leave alone", so a null
 * there could not be distinguished from absence. A {@link Rate} whose inner `value` is null is a
 * different thing entirely and perfectly sendable: it states an unconstrained capacity, in a unit.
 *
 * **`caption` is the one field here whose empty string is a value** (FR-30): the backend reads a
 * present-but-blank caption on this endpoint as *clear it*, so emptying the field in the panel is an
 * ordinary edit on the ordinary path rather than the `ReplaceNodeCommand` every other clear takes.
 * `element-captions.ts` is where that rule is stated and specced.
 */
export type NodeAttributes = Partial<{
  name: string;
  type: NodeType;
  capacity: Rate;
  processingTime: Duration;
  fixedCost: number;
  varCost: number;
  failureProb: number;
  region: string;
  lat: number;
  lng: number;
  caption: string;
  captionVisible: boolean;
}>;

/** Link fields the property panel can change through a bulk PATCH. See {@link NodeAttributes}. */
export type LinkAttributes = Partial<{
  leadTime: Duration;
  capacity: Rate;
  unitCost: number;
  failureProb: number;
  caption: string;
  captionVisible: boolean;
}>;

/** One reversible editor action. `apply` is also what redo runs. */
export interface EditorCommand {
  /** Shown on the undo/redo tooltips and announced to screen readers, e.g. "Move 4 nodes". */
  readonly label: string;
  apply(ctx: CommandContext): Promise<void>;
  revert(ctx: CommandContext): Promise<void>;
}

// ------------------------------------------------------------------ concrete commands

/** Palette drop or double-click on empty canvas. */
export class AddNodeCommand implements EditorCommand {
  readonly label: string;
  /** Id from the most recent apply; resolved through the remap before use. */
  private createdId: Id | null = null;

  constructor(private readonly request: NodeRequest) {
    this.label = `Add ${request.type.toLowerCase()} "${request.name}"`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    const created = await ctx.createNode(this.request);
    if (this.createdId !== null && this.createdId !== created.id) {
      ctx.remapNode(this.createdId, created.id);
    }
    this.createdId = created.id;
    ctx.upsertNode(created);
    ctx.select([created.id], []);
  }

  async revert(ctx: CommandContext): Promise<void> {
    if (this.createdId === null) {
      return;
    }
    const id = ctx.node(this.createdId);
    await ctx.deleteNode(id);
    ctx.removeNode(id);
  }
}

/** The edge-handle gesture completing over a valid target. */
export class AddLinkCommand implements EditorCommand {
  readonly label: string;
  private createdId: Id | null = null;

  constructor(
    private readonly request: LinkRequest,
    sourceName: string,
    targetName: string,
  ) {
    this.label = `Connect ${sourceName} to ${targetName}`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    // Endpoints are resolved now, not at construction: either end may have been deleted and undone
    // since this command was first run, in which case it carries a new id.
    const created = await ctx.createLink({
      ...this.request,
      sourceNodeId: ctx.node(this.request.sourceNodeId),
      targetNodeId: ctx.node(this.request.targetNodeId),
    });
    if (this.createdId !== null && this.createdId !== created.id) {
      ctx.remapLink(this.createdId, created.id);
    }
    this.createdId = created.id;
    ctx.upsertLink(created);
    ctx.select([], [created.id]);
  }

  async revert(ctx: CommandContext): Promise<void> {
    if (this.createdId === null) {
      return;
    }
    const id = ctx.link(this.createdId);
    await ctx.deleteLink(id);
    ctx.removeLink(id);
  }
}

/** One node's before/after canvas coordinates. */
export interface NodeMove {
  readonly nodeId: Id;
  readonly from: { readonly posX: number; readonly posY: number };
  readonly to: { readonly posX: number; readonly posY: number };
}

/**
 * A drag of one node or of a whole selection, and the auto-layout button.
 *
 * Purely local plus a queued position patch - no await - so dragging stays at frame rate and the
 * whole move reaches the server as one entry in the next bulk flush.
 */
export class MoveNodesCommand implements EditorCommand {
  readonly label: string;

  constructor(
    private readonly moves: readonly NodeMove[],
    labelOverride?: string,
  ) {
    this.label = labelOverride ?? `Move ${moves.length} node${moves.length === 1 ? '' : 's'}`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    this.write(ctx, (move) => move.to);
  }

  async revert(ctx: CommandContext): Promise<void> {
    this.write(ctx, (move) => move.from);
  }

  private write(ctx: CommandContext, pick: (move: NodeMove) => NodeMove['to']): void {
    for (const move of this.moves) {
      const id = ctx.node(move.nodeId);
      const node = ctx.getNode(id);
      if (!node) {
        continue;
      }
      const { posX, posY } = pick(move);
      ctx.upsertNode({ ...node, posX, posY });
      ctx.queuePosition(id, posX, posY);
    }
  }
}

/** One node's before/after attribute values, holding only the fields that changed. */
export interface NodeEdit {
  readonly nodeId: Id;
  readonly before: NodeAttributes;
  readonly after: NodeAttributes;
}

/** A property-panel edit across one node or a multi-selection. */
export class EditNodesCommand implements EditorCommand {
  readonly label: string;

  constructor(
    private readonly edits: readonly NodeEdit[],
    fieldLabel: string,
  ) {
    this.label = `Set ${fieldLabel} on ${edits.length} node${edits.length === 1 ? '' : 's'}`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    this.write(ctx, (edit) => edit.after);
  }

  async revert(ctx: CommandContext): Promise<void> {
    this.write(ctx, (edit) => edit.before);
  }

  private write(ctx: CommandContext, pick: (edit: NodeEdit) => NodeAttributes): void {
    for (const edit of this.edits) {
      const id = ctx.node(edit.nodeId);
      const node = ctx.getNode(id);
      if (!node) {
        continue;
      }
      const attributes = pick(edit);
      // Empty when the field was null before the edit: a PATCH cannot write null back, so there is
      // nothing to send and nothing to apply. Undoing "set a region on a node that had none" leaves
      // the region - clearing it is the panel's × button, which is a PUT.
      //
      // **A caption is the exception and so is never empty here** (FR-30): its undo is the empty
      // string, which this one endpoint reads as "clear it", so "typed a caption, Ctrl-Z" removes
      // the caption instead of reporting success and leaving it drawn. The store builds that
      // `before` through `element-captions.previousAttributeValue`.
      if (!Object.keys(attributes).length) {
        continue;
      }
      ctx.upsertNode({ ...node, ...attributes });
      ctx.queueNodePatch(id, attributes);
    }
  }
}

/** One link's before/after attribute values. */
export interface LinkEdit {
  readonly linkId: Id;
  readonly before: LinkAttributes;
  readonly after: LinkAttributes;
}

/** A property-panel edit across one link or a multi-selection. */
export class EditLinksCommand implements EditorCommand {
  readonly label: string;

  constructor(
    private readonly edits: readonly LinkEdit[],
    fieldLabel: string,
  ) {
    this.label = `Set ${fieldLabel} on ${edits.length} link${edits.length === 1 ? '' : 's'}`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    this.write(ctx, (edit) => edit.after);
  }

  async revert(ctx: CommandContext): Promise<void> {
    this.write(ctx, (edit) => edit.before);
  }

  private write(ctx: CommandContext, pick: (edit: LinkEdit) => LinkAttributes): void {
    for (const edit of this.edits) {
      const id = ctx.link(edit.linkId);
      const link = ctx.getLink(id);
      if (!link) {
        continue;
      }
      const attributes = pick(edit);
      // See `EditNodesCommand.write`: an empty patch means the field was null before the edit.
      if (!Object.keys(attributes).length) {
        continue;
      }
      ctx.upsertLink({ ...link, ...attributes });
      ctx.queueLinkPatch(id, attributes);
    }
  }
}

/**
 * Clearing a nullable node field back to unset.
 *
 * A separate command because it is a separate verb: `PATCH` leaves omitted fields alone, so it can
 * never express "make this null" - `NodeController` says so outright. Only `PUT /nodes/{id}` can,
 * and only for a single node, since a full replacement needs every other field's current value.
 */
export class ReplaceNodeCommand implements EditorCommand {
  readonly label: string;

  constructor(
    private readonly nodeId: Id,
    private readonly before: NodeRequest,
    private readonly after: NodeRequest,
    fieldLabel: string,
  ) {
    this.label = `Clear ${fieldLabel}`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    await this.write(ctx, this.after);
  }

  async revert(ctx: CommandContext): Promise<void> {
    await this.write(ctx, this.before);
  }

  private async write(ctx: CommandContext, request: NodeRequest): Promise<void> {
    // Position lives in the same row, and PUT clears what it omits - so send the coordinates the
    // node has right now rather than the ones captured when the panel was opened.
    const id = ctx.node(this.nodeId);
    const current = ctx.getNode(id);
    const updated = await ctx.replaceNode(id, {
      ...request,
      posX: current?.posX ?? request.posX ?? null,
      posY: current?.posY ?? request.posY ?? null,
    });
    ctx.upsertNode(updated);
  }
}

/**
 * Clearing a link's capacity back to unconstrained.
 *
 * The link counterpart of {@link ReplaceNodeCommand}, and for the same reason: `PATCH` cannot
 * express "make this null". `LinkAttributesRequest` carries no endpoints - they are not editable -
 * so a full replacement here is genuinely just the four attributes.
 */
export class ReplaceLinkCommand implements EditorCommand {
  readonly label: string;

  constructor(
    private readonly linkId: Id,
    private readonly before: LinkAttributesRequest,
    private readonly after: LinkAttributesRequest,
    fieldLabel: string,
  ) {
    this.label = `Clear ${fieldLabel}`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    ctx.upsertLink(await ctx.replaceLink(ctx.link(this.linkId), this.after));
  }

  async revert(ctx: CommandContext): Promise<void> {
    ctx.upsertLink(await ctx.replaceLink(ctx.link(this.linkId), this.before));
  }
}

/**
 * A node–product row written through `PUT /nodes/{id}/products/{productId}`.
 *
 * The endpoint is an upsert and a full replacement in one, so this single command covers creating a
 * row, editing its demand or holding cost, and - through `before` - undoing either. When there was
 * no row to begin with, `before` is null and the undo is a DELETE.
 *
 * Not batched. There is no bulk endpoint for per-product rows, and no gesture that produces them in
 * bulk: they are typed one at a time in the panel.
 */
export class UpsertNodeProductCommand implements EditorCommand {
  readonly label: string;

  constructor(
    private readonly nodeId: Id,
    private readonly productId: Id,
    private readonly before: NodeProductRequest | null,
    private readonly after: NodeProductRequest,
    productName: string,
    fieldLabel: string,
  ) {
    this.label = before
      ? `Set ${fieldLabel} for ${productName}`
      : `Add ${productName} to this node`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    const nodeId = ctx.node(this.nodeId);
    ctx.setNodeProduct(await ctx.upsertNodeProduct(nodeId, this.productId, this.after));
  }

  async revert(ctx: CommandContext): Promise<void> {
    const nodeId = ctx.node(this.nodeId);
    if (!this.before) {
      await ctx.deleteNodeProduct(nodeId, this.productId);
      ctx.dropNodeProduct(nodeId, this.productId);
      return;
    }
    ctx.setNodeProduct(await ctx.upsertNodeProduct(nodeId, this.productId, this.before));
  }
}

/** Taking a product off a node. The mirror of {@link UpsertNodeProductCommand}. */
export class RemoveNodeProductCommand implements EditorCommand {
  readonly label: string;

  constructor(
    private readonly nodeId: Id,
    private readonly productId: Id,
    private readonly snapshot: NodeProductRequest,
    productName: string,
  ) {
    this.label = `Remove ${productName} from this node`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    const nodeId = ctx.node(this.nodeId);
    await ctx.deleteNodeProduct(nodeId, this.productId);
    ctx.dropNodeProduct(nodeId, this.productId);
  }

  async revert(ctx: CommandContext): Promise<void> {
    const nodeId = ctx.node(this.nodeId);
    ctx.setNodeProduct(await ctx.upsertNodeProduct(nodeId, this.productId, this.snapshot));
  }
}

/** Everything a delete removed, kept so undo can put it back. */
interface DeletedSnapshot {
  readonly nodes: readonly NetworkNode[];
  /** Explicitly selected links *and* every link incident to a deleted node, which cascades. */
  readonly links: readonly NetworkLink[];
}

/**
 * Delete of the current selection, after the confirm that lists dependent data.
 *
 * Deleting a node cascades its links on the server, so the snapshot has to capture those too or the
 * undo would restore islands. Links go first on the way out and last on the way back in, which is
 * the only ordering that never references a node that does not exist.
 */
export class DeleteElementsCommand implements EditorCommand {
  readonly label: string;
  private snapshot: DeletedSnapshot = { nodes: [], links: [] };

  constructor(
    private readonly nodeIds: readonly Id[],
    private readonly linkIds: readonly Id[],
  ) {
    const parts: string[] = [];
    if (nodeIds.length) {
      parts.push(`${nodeIds.length} node${nodeIds.length === 1 ? '' : 's'}`);
    }
    if (linkIds.length) {
      parts.push(`${linkIds.length} link${linkIds.length === 1 ? '' : 's'}`);
    }
    this.label = `Delete ${parts.join(' and ')}`;
  }

  async apply(ctx: CommandContext): Promise<void> {
    const nodes: NetworkNode[] = [];
    const links = new Map<Id, NetworkLink>();

    for (const linkId of this.linkIds) {
      const link = ctx.getLink(ctx.link(linkId));
      if (link) {
        links.set(link.id, link);
      }
    }
    for (const nodeId of this.nodeIds) {
      const id = ctx.node(nodeId);
      const node = ctx.getNode(id);
      if (!node) {
        continue;
      }
      nodes.push(node);
      for (const link of ctx.incidentLinks(id)) {
        links.set(link.id, link);
      }
    }
    this.snapshot = { nodes, links: [...links.values()] };

    // Links explicitly, before their endpoints: the server would cascade them anyway, but doing it
    // in the open keeps the client's picture and the server's identical at every step.
    for (const link of this.snapshot.links) {
      await ctx.deleteLink(link.id);
      ctx.removeLink(link.id);
    }
    for (const node of nodes) {
      await ctx.deleteNode(node.id);
      ctx.removeNode(node.id);
    }
    ctx.select([], []);
  }

  async revert(ctx: CommandContext): Promise<void> {
    for (const node of this.snapshot.nodes) {
      const recreated = await ctx.createNode(toNodeRequest(node));
      if (recreated.id !== node.id) {
        ctx.remapNode(node.id, recreated.id);
      }
      ctx.upsertNode(recreated);
    }
    for (const link of this.snapshot.links) {
      const source = ctx.node(link.sourceNodeId);
      const target = ctx.node(link.targetNodeId);
      if (!ctx.getNode(source) || !ctx.getNode(target)) {
        // An endpoint was deleted by a later command that has not been undone yet. Skip rather than
        // fail: undoing further back will restore it, and this link with it.
        continue;
      }
      const recreated = await ctx.createLink({
        sourceNodeId: source,
        targetNodeId: target,
        leadTime: link.leadTime,
        capacity: link.capacity,
        unitCost: link.unitCost,
        failureProb: link.failureProb,
        // Every attribute the snapshot holds, captions included: an omitted `caption` means "none"
        // on a create, so undoing the delete of a captioned arc would put it back stripped of the
        // annotation that made it worth reading (FR-30). `captionVisible` travels for the same
        // reason and against a sharper default - omitting it means *visible*, so a caption
        // deliberately hidden would come back on screen.
        caption: link.caption,
        captionVisible: link.captionVisible,
      });
      if (recreated.id !== link.id) {
        ctx.remapLink(link.id, recreated.id);
      }
      ctx.upsertLink(recreated);
    }
    ctx.select(
      this.snapshot.nodes.map((node) => ctx.node(node.id)),
      [],
    );
  }
}

/**
 * A node as the request that would re-create it identically.
 *
 * Every field, including the two unit-bearing ones and the caption pair: a PUT clears what it omits,
 * so a request built to change one thing has to carry the rest or it would quietly zero a processing
 * time and unconstrain a capacity on the way past - and, since FR-30, wipe the caption of
 * any node whose region or capacity was cleared, and re-show one that had been hidden.
 *
 * Both readers of this function make that concrete: `ReplaceNodeCommand` clears one field through a
 * full replacement, and `DeleteElementsCommand` re-creates a deleted node from its snapshot.
 */
export function toNodeRequest(node: NetworkNode): NodeRequest {
  return {
    name: node.name,
    type: node.type,
    capacity: node.capacity,
    processingTime: node.processingTime,
    fixedCost: node.fixedCost,
    varCost: node.varCost,
    failureProb: node.failureProb,
    region: node.region,
    lat: node.lat,
    lng: node.lng,
    posX: node.posX,
    posY: node.posY,
    caption: node.caption,
    captionVisible: node.captionVisible,
  };
}

/**
 * A node–product row as the request that would write it back unchanged.
 *
 * The PUT is a full replacement, so an omitted rate becomes zero: the four values travel together
 * even when the user only touched one of them.
 */
export function toNodeProductRequest(row: NodeProduct): NodeProductRequest {
  return {
    demand: row.demand,
    initialInventory: row.initialInventory,
    safetyStock: row.safetyStock,
    holdingCost: row.holdingCost,
  };
}

// ------------------------------------------------------------------------- the stack

/** How many steps back the editor can go. Beyond this the oldest entry is dropped. */
export const UNDO_DEPTH = 100;

/**
 * Undo/redo over {@link EditorCommand}s, serialised (Ctrl-Z / Ctrl-Y).
 *
 * Serialised because add and delete are network calls: two overlapping undos could delete a node
 * before the link that references it, or re-create a link against an endpoint whose id is still in
 * flight. Every operation joins one promise chain, and {@link busy} is true while the chain is
 * running so the keyboard handler can ignore repeats.
 */
export class CommandStack {
  private readonly undoStack: EditorCommand[] = [];
  private readonly redoStack: EditorCommand[] = [];
  private chain: Promise<void> = Promise.resolve();

  private readonly _busy = signal(false);
  private readonly _canUndo = signal(false);
  private readonly _canRedo = signal(false);
  private readonly _nextUndo = signal<string | null>(null);
  private readonly _nextRedo = signal<string | null>(null);

  readonly busy = this._busy.asReadonly();
  readonly canUndo = this._canUndo.asReadonly();
  readonly canRedo = this._canRedo.asReadonly();
  /** Label of the command Ctrl-Z would reverse, for the button tooltip. */
  readonly nextUndo = this._nextUndo.asReadonly();
  /** Label of the command Ctrl-Y would replay. */
  readonly nextRedo = this._nextRedo.asReadonly();

  /** Run a command and record it. Rejects - and records nothing - if `apply` fails. */
  push(ctx: CommandContext, command: EditorCommand): Promise<void> {
    return this.enqueue(async () => {
      await command.apply(ctx);
      this.undoStack.push(command);
      if (this.undoStack.length > UNDO_DEPTH) {
        this.undoStack.shift();
      }
      // A new action invalidates the redo branch: there is no longer a future to return to.
      this.redoStack.length = 0;
    });
  }

  undo(ctx: CommandContext): Promise<void> {
    return this.enqueue(async () => {
      const command = this.undoStack.pop();
      if (!command) {
        return;
      }
      try {
        await command.revert(ctx);
        this.redoStack.push(command);
      } catch (failure) {
        // Put it back: the editor still shows the state this command produced, so the stack must
        // keep describing it. Losing the entry would strand every entry beneath it.
        this.undoStack.push(command);
        throw failure;
      }
    });
  }

  redo(ctx: CommandContext): Promise<void> {
    return this.enqueue(async () => {
      const command = this.redoStack.pop();
      if (!command) {
        return;
      }
      try {
        await command.apply(ctx);
        this.undoStack.push(command);
      } catch (failure) {
        this.redoStack.push(command);
        throw failure;
      }
    });
  }

  /** Forget everything - on load, and after a fork, where the ids no longer refer to this network. */
  clear(): void {
    this.undoStack.length = 0;
    this.redoStack.length = 0;
    this.publish();
  }

  private enqueue(work: () => Promise<void>): Promise<void> {
    const run = this.chain.then(
      () => {
        this._busy.set(true);
        return work();
      },
      () => {
        // A previous failure must not poison the chain for everything queued behind it.
        this._busy.set(true);
        return work();
      },
    );
    this.chain = run.then(
      () => this.settle(),
      () => this.settle(),
    );
    return run;
  }

  private settle(): void {
    this._busy.set(false);
    this.publish();
  }

  private publish(): void {
    this._canUndo.set(this.undoStack.length > 0);
    this._canRedo.set(this.redoStack.length > 0);
    this._nextUndo.set(this.undoStack.at(-1)?.label ?? null);
    this._nextRedo.set(this.redoStack.at(-1)?.label ?? null);
  }
}

/**
 * Follows a remap chain to the id an entity currently holds.
 *
 * Shared by the store's `node`/`link` resolvers. The visited set is a cycle guard: nothing should
 * ever produce `a -> b -> a`, but an infinite loop inside an undo handler is a hung tab, and the
 * check costs nothing.
 */
export function resolveRemapped(remap: ReadonlyMap<Id, Id>, id: Id): Id {
  let current = id;
  const seen = new Set<Id>([id]);
  for (;;) {
    const next = remap.get(current);
    if (next === undefined || seen.has(next)) {
      return current;
    }
    seen.add(next);
    current = next;
  }
}
