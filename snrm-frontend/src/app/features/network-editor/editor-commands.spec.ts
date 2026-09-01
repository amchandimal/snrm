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
  Rate,
} from '../../core/models';
import {
  AddLinkCommand,
  AddNodeCommand,
  CommandContext,
  CommandStack,
  DeleteElementsCommand,
  EditLinksCommand,
  EditNodesCommand,
  LinkAttributes,
  MoveNodesCommand,
  NodeAttributes,
  RemoveNodeProductCommand,
  UpsertNodeProductCommand,
  resolveRemapped,
} from './editor-commands';

/** The network's clock in these specs: one day, so a lead time in hours is worth restating. */
const ONE_DAY: Duration = { value: 1, unit: 'DAY' };
const UNCONSTRAINED: Rate = { value: null, timeUnit: 'DAY' };

/**
 * An in-memory {@link CommandContext}.
 *
 * Hands out a fresh id on every create, exactly as MySQL does - which is the whole reason the remap
 * table exists, and the thing these specs are really testing.
 */
class FakeContext implements CommandContext {
  readonly networkId: Id = 7;

  readonly nodes = new Map<Id, NetworkNode>();
  readonly links = new Map<Id, NetworkLink>();
  readonly nodeProducts = new Map<string, NodeProduct>();
  readonly nodeRemap = new Map<Id, Id>();
  readonly linkRemap = new Map<Id, Id>();
  readonly queuedPositions: { id: Id; posX: number; posY: number }[] = [];
  readonly queuedNodePatches: { id: Id; patch: NodeAttributes }[] = [];
  readonly queuedLinkPatches: { id: Id; patch: LinkAttributes }[] = [];
  readonly calls: string[] = [];
  selection: { nodeIds: readonly Id[]; linkIds: readonly Id[] } = { nodeIds: [], linkIds: [] };

  private nextId = 100;

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
    return this.nodes.get(id);
  }

  getLink(id: Id): NetworkLink | undefined {
    return this.links.get(id);
  }

  incidentLinks(nodeId: Id): readonly NetworkLink[] {
    return [...this.links.values()].filter(
      (link) => link.sourceNodeId === nodeId || link.targetNodeId === nodeId,
    );
  }

  getNodeProduct(nodeId: Id, productId: Id): NodeProduct | undefined {
    return this.nodeProducts.get(rowKey(nodeId, productId));
  }

  upsertNode(node: NetworkNode): void {
    this.nodes.set(node.id, node);
  }

  upsertLink(link: NetworkLink): void {
    this.links.set(link.id, link);
  }

  setNodeProduct(row: NodeProduct): void {
    this.nodeProducts.set(rowKey(row.nodeId, row.productId), row);
  }

  dropNodeProduct(nodeId: Id, productId: Id): void {
    this.nodeProducts.delete(rowKey(nodeId, productId));
  }

  removeNode(id: Id): void {
    this.nodes.delete(id);
    for (const [linkId, link] of [...this.links]) {
      if (link.sourceNodeId === id || link.targetNodeId === id) {
        this.links.delete(linkId);
      }
    }
  }

  removeLink(id: Id): void {
    this.links.delete(id);
  }

  select(nodeIds: readonly Id[], linkIds: readonly Id[]): void {
    this.selection = { nodeIds, linkIds };
  }

  async createNode(request: NodeRequest): Promise<NetworkNode> {
    const id = this.nextId++;
    this.calls.push(`createNode:${request.name}`);
    return {
      id,
      networkId: this.networkId,
      name: request.name,
      type: request.type,
      // The server's fallback: an omitted rate is unconstrained and an omitted duration
      // zero, both in the network's period unit.
      capacity: request.capacity ?? UNCONSTRAINED,
      processingTime: request.processingTime ?? { value: 0, unit: 'DAY' },
      fixedCost: request.fixedCost ?? 0,
      varCost: request.varCost ?? 0,
      failureProb: request.failureProb ?? 0,
      region: request.region ?? null,
      lat: request.lat ?? null,
      lng: request.lng ?? null,
      posX: request.posX ?? null,
      posY: request.posY ?? null,
      caption: replaceCaption(request.caption),
      captionVisible: replaceCaptionVisible(request.captionVisible),
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
  }

  async deleteNode(id: Id): Promise<void> {
    this.calls.push(`deleteNode:${id}`);
  }

  async createLink(request: LinkRequest): Promise<NetworkLink> {
    const id = this.nextId++;
    this.calls.push(`createLink:${request.sourceNodeId}->${request.targetNodeId}`);
    return {
      id,
      networkId: this.networkId,
      sourceNodeId: request.sourceNodeId,
      targetNodeId: request.targetNodeId,
      leadTime: request.leadTime ?? { value: 0, unit: 'DAY' },
      capacity: request.capacity ?? UNCONSTRAINED,
      unitCost: request.unitCost ?? 0,
      failureProb: request.failureProb ?? 0,
      caption: replaceCaption(request.caption),
      captionVisible: replaceCaptionVisible(request.captionVisible),
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
  }

  async deleteLink(id: Id): Promise<void> {
    this.calls.push(`deleteLink:${id}`);
  }

  async replaceNode(id: Id, request: NodeRequest): Promise<NetworkNode> {
    const created = await this.createNode(request);
    return { ...created, id };
  }

  async replaceLink(id: Id, request: LinkAttributesRequest): Promise<NetworkLink> {
    const existing = this.links.get(id)!;
    return {
      ...existing,
      // A PUT clears what it omits: no lead time means zero transit, no capacity means
      // unconstrained.
      leadTime: request.leadTime ?? { value: 0, unit: 'DAY' },
      capacity: request.capacity ?? UNCONSTRAINED,
      unitCost: request.unitCost ?? existing.unitCost,
      failureProb: request.failureProb ?? existing.failureProb,
      // A PUT clears an omitted caption and reads an omitted flag as *visible* - the asymmetry
      // `com.snrm.network.Captions` states, modelled here so a full replacement that forgot the
      // pair fails a test rather than wiping an annotation on the way past (FR-30).
      caption: replaceCaption(request.caption),
      captionVisible: replaceCaptionVisible(request.captionVisible),
    };
  }

  async upsertNodeProduct(
    nodeId: Id,
    productId: Id,
    request: NodeProductRequest,
  ): Promise<NodeProduct> {
    this.calls.push(`upsertNodeProduct:${nodeId}/${productId}`);
    return {
      nodeId,
      productId,
      productName: `Product ${productId}`,
      demand: request.demand ?? { value: 0, timeUnit: 'DAY' },
      initialInventory: request.initialInventory ?? 0,
      safetyStock: request.safetyStock ?? 0,
      holdingCost: request.holdingCost ?? { value: 0, timeUnit: 'DAY' },
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
  }

  async deleteNodeProduct(nodeId: Id, productId: Id): Promise<void> {
    this.calls.push(`deleteNodeProduct:${nodeId}/${productId}`);
  }

  queuePosition(id: Id, posX: number, posY: number): void {
    this.queuedPositions.push({ id, posX, posY });
  }

  queueNodePatch(id: Id, patch: NodeAttributes): void {
    this.queuedNodePatches.push({ id, patch });
  }

  queueLinkPatch(id: Id, patch: LinkAttributes): void {
    this.queuedLinkPatches.push({ id, patch });
  }

  async flush(): Promise<void> {
    this.calls.push('flush');
  }
}

function nodeRequest(name: string, posX = 0, posY = 0): NodeRequest {
  return {
    name,
    type: 'DC',
    capacity: UNCONSTRAINED,
    processingTime: { value: 0, unit: 'DAY' },
    fixedCost: 0,
    varCost: 0,
    failureProb: 0,
    region: null,
    lat: null,
    lng: null,
    posX,
    posY,
  };
}

/**
 * `com.snrm.network.Captions.replace`, as a create and a PUT mean it (FR-30).
 *
 * A caption is trimmed on every write path and a blank one is stored as **null**, so there is one
 * form of "no caption" and no reader has to decide what `''` in the column means.
 */
function replaceCaption(submitted: string | null | undefined): string | null {
  const trimmed = (submitted ?? '').trim();
  return trimmed.length === 0 ? null : trimmed;
}

/** `Captions.replaceVisible`: an omitted flag is **visible**, never hidden (FR-30, V10). */
function replaceCaptionVisible(submitted: boolean | undefined): boolean {
  return submitted === undefined || submitted;
}

/** `(node, product)` is the natural key of a per-product row, so the fake keys its map by both. */
function rowKey(nodeId: Id, productId: Id): string {
  return `${nodeId}:${productId}`;
}

describe('resolveRemapped', () => {
  it('returns the id unchanged when nothing was re-created', () => {
    expect(resolveRemapped(new Map(), 5)).toBe(5);
  });

  it('follows a chain of re-creations', () => {
    const remap = new Map([
      [5, 9],
      [9, 12],
    ]);
    expect(resolveRemapped(remap, 5)).toBe(12);
  });

  it('stops on a cycle rather than hanging the tab', () => {
    const remap = new Map([
      [1, 2],
      [2, 1],
    ]);
    expect(resolveRemapped(remap, 1)).toBe(2);
  });
});

describe('AddNodeCommand', () => {
  it('creates on apply and deletes on revert', async () => {
    const ctx = new FakeContext();
    const command = new AddNodeCommand(nodeRequest('DC 1'));

    await command.apply(ctx);
    expect(ctx.nodes.size).toBe(1);
    const created = [...ctx.nodes.values()][0];
    expect(ctx.selection.nodeIds).toEqual([created.id]);

    await command.revert(ctx);
    expect(ctx.nodes.size).toBe(0);
    expect(ctx.calls).toContain(`deleteNode:${created.id}`);
  });

  it('records a remap when redo re-creates the node under a new id', async () => {
    const ctx = new FakeContext();
    const command = new AddNodeCommand(nodeRequest('DC 1'));

    await command.apply(ctx);
    const firstId = [...ctx.nodes.keys()][0];
    await command.revert(ctx);
    await command.apply(ctx);
    const secondId = [...ctx.nodes.keys()][0];

    expect(secondId).not.toBe(firstId);
    expect(ctx.node(firstId)).toBe(secondId);
  });
});

describe('AddLinkCommand', () => {
  it('resolves endpoints through the remap at apply time, not at construction', async () => {
    const ctx = new FakeContext();
    const addSource = new AddNodeCommand(nodeRequest('Source'));
    const addTarget = new AddNodeCommand(nodeRequest('Target'));
    await addSource.apply(ctx);
    const sourceId = [...ctx.nodes.keys()][0];
    await addTarget.apply(ctx);
    const targetId = [...ctx.nodes.keys()].filter((id) => id !== sourceId)[0];

    const addLink = new AddLinkCommand(
      { sourceNodeId: sourceId, targetNodeId: targetId, leadTime: ONE_DAY },
      'Source',
      'Target',
    );
    await addLink.apply(ctx);
    expect(ctx.links.size).toBe(1);

    // The source is deleted and restored, so it now holds a different id.
    await addLink.revert(ctx);
    await addSource.revert(ctx);
    await addSource.apply(ctx);
    const newSourceId = ctx.node(sourceId);
    expect(newSourceId).not.toBe(sourceId);

    await addLink.apply(ctx);
    const link = [...ctx.links.values()][0];
    expect(link.sourceNodeId).toBe(newSourceId);
  });
});

describe('MoveNodesCommand', () => {
  it('queues a position patch rather than calling the API', async () => {
    const ctx = new FakeContext();
    ctx.upsertNode({ ...(await ctx.createNode(nodeRequest('DC 1'))), id: 1 });

    const command = new MoveNodesCommand([
      { nodeId: 1, from: { posX: 0, posY: 0 }, to: { posX: 40, posY: 60 } },
    ]);
    await command.apply(ctx);

    expect(ctx.nodes.get(1)?.posX).toBe(40);
    expect(ctx.queuedPositions).toEqual([{ id: 1, posX: 40, posY: 60 }]);
    expect(ctx.calls.filter((call) => call.startsWith('createNode')).length).toBe(1);

    await command.revert(ctx);
    expect(ctx.nodes.get(1)?.posX).toBe(0);
    expect(ctx.queuedPositions.at(-1)).toEqual({ id: 1, posX: 0, posY: 0 });
  });

  it('labels a multi-node drag as one step', () => {
    const command = new MoveNodesCommand([
      { nodeId: 1, from: { posX: 0, posY: 0 }, to: { posX: 1, posY: 1 } },
      { nodeId: 2, from: { posX: 0, posY: 0 }, to: { posX: 1, posY: 1 } },
    ]);
    expect(command.label).toBe('Move 2 nodes');
  });
});

describe('EditNodesCommand', () => {
  it('sends only the edited field, and restores the captured value on revert', async () => {
    const ctx = new FakeContext();
    const base = await ctx.createNode(nodeRequest('DC 1'));
    ctx.upsertNode({ ...base, id: 1, fixedCost: 10, varCost: 3 });

    const command = new EditNodesCommand(
      [{ nodeId: 1, before: { fixedCost: 10 }, after: { fixedCost: 25 } }],
      'fixed cost',
    );
    await command.apply(ctx);

    expect(ctx.nodes.get(1)?.fixedCost).toBe(25);
    // The untouched field must survive: that is what a PATCH's leave-alone semantics buy.
    expect(ctx.nodes.get(1)?.varCost).toBe(3);
    expect(ctx.queuedNodePatches).toEqual([{ id: 1, patch: { fixedCost: 25 } }]);

    await command.revert(ctx);
    expect(ctx.nodes.get(1)?.fixedCost).toBe(10);
  });

  it('undoes a caption typed onto a node that had none, by clearing it (FR-30)', async () => {
    const ctx = new FakeContext();
    const base = await ctx.createNode(nodeRequest('DC 1'));
    ctx.upsertNode({ ...base, id: 1 });
    expect(ctx.nodes.get(1)?.caption).toBeNull();

    // The `before` the store builds through `element-captions.previousAttributeValue`: the empty
    // string, which is the ONE value a bulk PATCH can use to remove something.
    const command = new EditNodesCommand(
      [{ nodeId: 1, before: { caption: '' }, after: { caption: 'Nordic hub - 3PL operated' } }],
      'caption',
    );
    await command.apply(ctx);

    expect(ctx.nodes.get(1)?.caption).toBe('Nordic hub - 3PL operated');
    expect(ctx.queuedNodePatches).toEqual([
      { id: 1, patch: { caption: 'Nordic hub - 3PL operated' } },
    ]);

    await command.revert(ctx);
    // Not "still there, having reported success" - which is what an omitted `before` would give,
    // and what every other nullable field on this endpoint has to settle for.
    expect(ctx.nodes.get(1)?.caption).toBe('');
    expect(ctx.queuedNodePatches.at(-1)).toEqual({ id: 1, patch: { caption: '' } });
  });

  it('hides captions across a selection and shows them again on undo (FR-30)', async () => {
    const ctx = new FakeContext();
    const first = await ctx.createNode({ ...nodeRequest('DC 1'), caption: 'Nordic hub' });
    const second = await ctx.createNode({ ...nodeRequest('DC 2'), caption: 'Southern hub' });
    ctx.upsertNode(first);
    ctx.upsertNode(second);

    // The checkbox bulk-applies where the text does not: hiding every caption at once is what
    // preparing a figure wants, and the flag is never null so it needs no exception of its own.
    const command = new EditNodesCommand(
      [
        { nodeId: first.id, before: { captionVisible: true }, after: { captionVisible: false } },
        { nodeId: second.id, before: { captionVisible: true }, after: { captionVisible: false } },
      ],
      'caption visibility',
    );
    await command.apply(ctx);

    expect(ctx.nodes.get(first.id)?.captionVisible).toBeFalse();
    expect(ctx.nodes.get(second.id)?.captionVisible).toBeFalse();
    // The text is untouched - hidden, not lost.
    expect(ctx.nodes.get(first.id)?.caption).toBe('Nordic hub');

    await command.revert(ctx);
    expect(ctx.nodes.get(first.id)?.captionVisible).toBeTrue();
    expect(ctx.nodes.get(second.id)?.captionVisible).toBeTrue();
  });
});

describe('EditLinksCommand with units', () => {
  it('restates a lead time as one step, and undo brings the declared pair back exactly', async () => {
    const ctx = new FakeContext();
    const source = await ctx.createNode(nodeRequest('Source'));
    const target = await ctx.createNode(nodeRequest('Target'));
    ctx.upsertNode(source);
    ctx.upsertNode(target);
    const link = await ctx.createLink({
      sourceNodeId: source.id,
      targetNodeId: target.id,
      leadTime: { value: 36, unit: 'HOUR' },
    });
    ctx.upsertLink(link);

    // What the unit dropdown produces: the same length of time, said differently.
    const command = new EditLinksCommand(
      [
        {
          linkId: link.id,
          before: { leadTime: { value: 36, unit: 'HOUR' } },
          after: { leadTime: { value: 1.5, unit: 'DAY' } },
        },
      ],
      'lead time unit',
    );

    await command.apply(ctx);
    expect(ctx.links.get(link.id)?.leadTime).toEqual({ value: 1.5, unit: 'DAY' });
    expect(ctx.queuedLinkPatches).toEqual([
      { id: link.id, patch: { leadTime: { value: 1.5, unit: 'DAY' } } },
    ]);

    await command.revert(ctx);
    expect(ctx.links.get(link.id)?.leadTime).toEqual({ value: 36, unit: 'HOUR' });
  });
});

describe('UpsertNodeProductCommand', () => {
  it('creates a row and removes it again on undo', async () => {
    const ctx = new FakeContext();
    const node = await ctx.createNode(nodeRequest('DC 1'));
    ctx.upsertNode(node);

    const command = new UpsertNodeProductCommand(
      node.id,
      5,
      null,
      { demand: { value: 120, timeUnit: 'WEEK' }, initialInventory: 0, safetyStock: 0 },
      'Gearbox',
      'demand',
    );

    await command.apply(ctx);
    expect(ctx.getNodeProduct(node.id, 5)?.demand).toEqual({ value: 120, timeUnit: 'WEEK' });
    expect(command.label).toBe('Add Gearbox to this node');

    // There was no row before, so the only honest undo is a delete.
    await command.revert(ctx);
    expect(ctx.getNodeProduct(node.id, 5)).toBeUndefined();
    expect(ctx.calls).toContain(`deleteNodeProduct:${node.id}/5`);
  });

  it('restores the previous figures when the row already existed', async () => {
    const ctx = new FakeContext();
    const node = await ctx.createNode(nodeRequest('DC 1'));
    ctx.upsertNode(node);
    ctx.setNodeProduct(
      await ctx.upsertNodeProduct(node.id, 5, { demand: { value: 120, timeUnit: 'WEEK' } }),
    );

    const command = new UpsertNodeProductCommand(
      node.id,
      5,
      { demand: { value: 120, timeUnit: 'WEEK' } },
      { demand: { value: 17.142857, timeUnit: 'DAY' } },
      'Gearbox',
      'demand unit',
    );

    await command.apply(ctx);
    expect(ctx.getNodeProduct(node.id, 5)?.demand.timeUnit).toBe('DAY');

    await command.revert(ctx);
    expect(ctx.getNodeProduct(node.id, 5)?.demand).toEqual({ value: 120, timeUnit: 'WEEK' });
  });
});

describe('RemoveNodeProductCommand', () => {
  it('puts the row back with its figures on undo', async () => {
    const ctx = new FakeContext();
    const node = await ctx.createNode(nodeRequest('DC 1'));
    ctx.upsertNode(node);
    ctx.setNodeProduct(
      await ctx.upsertNodeProduct(node.id, 5, {
        demand: { value: 120, timeUnit: 'WEEK' },
        safetyStock: 50,
      }),
    );

    const command = new RemoveNodeProductCommand(
      node.id,
      5,
      { demand: { value: 120, timeUnit: 'WEEK' }, safetyStock: 50 },
      'Gearbox',
    );

    await command.apply(ctx);
    expect(ctx.getNodeProduct(node.id, 5)).toBeUndefined();

    await command.revert(ctx);
    expect(ctx.getNodeProduct(node.id, 5)?.safetyStock).toBe(50);
    expect(ctx.getNodeProduct(node.id, 5)?.demand).toEqual({ value: 120, timeUnit: 'WEEK' });
  });
});

describe('DeleteElementsCommand', () => {
  it('captures cascading links and restores them with remapped endpoints', async () => {
    const ctx = new FakeContext();
    const source = await ctx.createNode(nodeRequest('Source'));
    const target = await ctx.createNode(nodeRequest('Target'));
    ctx.upsertNode(source);
    ctx.upsertNode(target);
    const link = await ctx.createLink({ sourceNodeId: source.id, targetNodeId: target.id });
    ctx.upsertLink(link);

    // Only the node is selected; its link must go with it and come back with it.
    const command = new DeleteElementsCommand([source.id], []);
    await command.apply(ctx);
    expect(ctx.nodes.has(source.id)).toBeFalse();
    expect(ctx.links.size).toBe(0);
    expect(ctx.calls).toContain(`deleteLink:${link.id}`);
    expect(ctx.calls.indexOf(`deleteLink:${link.id}`)).toBeLessThan(
      ctx.calls.indexOf(`deleteNode:${source.id}`),
    );

    await command.revert(ctx);
    expect(ctx.nodes.size).toBe(2);
    expect(ctx.links.size).toBe(1);
    const restored = [...ctx.links.values()][0];
    expect(restored.sourceNodeId).toBe(ctx.node(source.id));
    expect(restored.targetNodeId).toBe(target.id);
  });

  it('restores the captions of everything it deleted, hidden ones included (FR-30)', async () => {
    const ctx = new FakeContext();
    const source = await ctx.createNode({
      ...nodeRequest('Source'),
      caption: 'Sole source - 6-week qualification',
    });
    const target = await ctx.createNode({
      ...nodeRequest('Target'),
      // Written, kept, not drawn. An undo that re-showed it would be as wrong as one that lost it.
      caption: 'Two lines; second one idle since Q1',
      captionVisible: false,
    });
    ctx.upsertNode(source);
    ctx.upsertNode(target);
    const link = await ctx.createLink({
      sourceNodeId: source.id,
      targetNodeId: target.id,
      caption: 'Contracted road leg, no alternative carrier',
    });
    ctx.upsertLink(link);

    const command = new DeleteElementsCommand([source.id, target.id], []);
    await command.apply(ctx);
    await command.revert(ctx);

    const nodes = [...ctx.nodes.values()];
    expect(nodes.find((node) => node.name === 'Source')?.caption).toBe(
      'Sole source - 6-week qualification',
    );
    const restoredTarget = nodes.find((node) => node.name === 'Target');
    expect(restoredTarget?.caption).toBe('Two lines; second one idle since Q1');
    expect(restoredTarget?.captionVisible).toBeFalse();
    // The arc's caption travels on the re-create too - `createLink` names every field it wants kept.
    expect([...ctx.links.values()][0].caption).toBe('Contracted road leg, no alternative carrier');
  });
});

describe('CommandStack', () => {
  it('tracks canUndo and canRedo', async () => {
    const ctx = new FakeContext();
    const stack = new CommandStack();
    expect(stack.canUndo()).toBeFalse();

    await stack.push(ctx, new AddNodeCommand(nodeRequest('DC 1')));
    expect(stack.canUndo()).toBeTrue();
    expect(stack.canRedo()).toBeFalse();
    expect(stack.nextUndo()).toBe('Add dc "DC 1"');

    await stack.undo(ctx);
    expect(stack.canUndo()).toBeFalse();
    expect(stack.canRedo()).toBeTrue();

    await stack.redo(ctx);
    expect(stack.canUndo()).toBeTrue();
    expect(stack.canRedo()).toBeFalse();
  });

  it('clears the redo branch when a new command is pushed', async () => {
    const ctx = new FakeContext();
    const stack = new CommandStack();
    await stack.push(ctx, new AddNodeCommand(nodeRequest('DC 1')));
    await stack.undo(ctx);
    expect(stack.canRedo()).toBeTrue();

    await stack.push(ctx, new AddNodeCommand(nodeRequest('DC 2')));
    expect(stack.canRedo()).toBeFalse();
  });

  it('records nothing when apply fails', async () => {
    const ctx = new FakeContext();
    const stack = new CommandStack();
    const failing = {
      label: 'boom',
      apply: () => Promise.reject(new Error('nope')),
      revert: () => Promise.resolve(),
    };

    await expectAsync(stack.push(ctx, failing)).toBeRejected();
    expect(stack.canUndo()).toBeFalse();
  });

  it('keeps the entry on the undo stack when revert fails', async () => {
    const ctx = new FakeContext();
    const stack = new CommandStack();
    const brittle = {
      label: 'brittle',
      apply: () => Promise.resolve(),
      revert: () => Promise.reject(new Error('server said no')),
    };

    await stack.push(ctx, brittle);
    await expectAsync(stack.undo(ctx)).toBeRejected();
    // The canvas still shows the state this command produced, so the stack must still describe it.
    expect(stack.canUndo()).toBeTrue();
    expect(stack.canRedo()).toBeFalse();
  });

  it('serialises overlapping operations', async () => {
    const ctx = new FakeContext();
    const stack = new CommandStack();
    const order: string[] = [];
    const slow = (name: string) => ({
      label: name,
      apply: async () => {
        order.push(`${name}:start`);
        await Promise.resolve();
        order.push(`${name}:end`);
      },
      revert: () => Promise.resolve(),
    });

    await Promise.all([stack.push(ctx, slow('a')), stack.push(ctx, slow('b'))]);
    expect(order).toEqual(['a:start', 'a:end', 'b:start', 'b:end']);
  });
});
