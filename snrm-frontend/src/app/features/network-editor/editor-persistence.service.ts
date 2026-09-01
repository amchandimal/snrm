import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import {
  WireLink,
  WireNode,
  normaliseLinks,
  normaliseNodes,
} from '../../core/api-nulls';
import { ApiService } from '../../core/api.service';
import {
  BulkLinkPatchRequest,
  BulkNodePatchRequest,
  BulkNodePositionRequest,
  Id,
  LinkPatch,
  MAX_BULK_BATCH,
  NetworkLink,
  NetworkNode,
  NodePatch,
  NodePositionPatch,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { LinkAttributes, NodeAttributes } from './editor-commands';

/**
 * `BulkNodeResponse` / `BulkLinkResponse` as the wire actually carries them.
 *
 * The elements a flush writes come back whole, and a null field is not sent at all
 * (`spring.jackson.default-property-inclusion=non_null`), so they arrive here exactly as they arrive
 * at `NetworkEditorStore.load` and go through the same normalisation. It matters most for the one
 * field this feature can *set* to null: clearing a caption answers with a node whose `caption` is
 * absent, and merging that in raw would leave `undefined` in the store where every reader -
 * including FR-30's own undo - tests for null (`core/api-nulls.ts`).
 */
interface WireBulkNodeResponse {
  readonly updated: number;
  readonly nodes: readonly WireNode[];
}

interface WireBulkLinkResponse {
  readonly updated: number;
  readonly links: readonly WireLink[];
}

/**
 * Debounced, coalescing persistence for canvas edits.
 *
 * > "Edits are debounced and PATCHed to the bulk endpoints every 2 s and on blur; a dirty indicator
 * > shows unsaved state."
 *
 * Three pending maps, one per bulk endpoint. Keyed by id, so dragging a node for four seconds
 * leaves **one** entry rather than sixty - the coalescing is what makes a two-second window cheap
 * rather than merely delayed.
 *
 * Only moves and attribute edits come through here. Creates and deletes go straight out as single
 * requests from `editor-commands.ts`, because the API has no bulk create or delete - `PATCH
 * /networks/{id}/nodes`, `/nodes/positions` and `/links` are the only batched verbs it offers.
 */
@Injectable()
export class EditorPersistenceService {
  private readonly api = inject(ApiService);

  /**
   * Milliseconds between the first unflushed edit and its flush.
   *
   * A ceiling from the *first* edit, not a trailing debounce reset by each one: a trailing debounce
   * never fires while the user keeps dragging, and the promise here is every 2 s.
   */
  static readonly FLUSH_INTERVAL_MS = 2000;

  private networkId: Id | null = null;

  private readonly positions = new Map<Id, NodePositionPatch>();
  private readonly nodePatches = new Map<Id, NodePatch>();
  private readonly linkPatches = new Map<Id, LinkPatch>();

  private timer: ReturnType<typeof setTimeout> | null = null;
  private chain: Promise<void> = Promise.resolve();
  private destroyed = false;

  private readonly _pending = signal(0);
  private readonly _saving = signal(false);
  private readonly _saveError = signal<string | null>(null);
  private readonly _lastSavedAt = signal<number | null>(null);

  /** Number of elements with unsent changes. */
  readonly pending = this._pending.asReadonly();
  readonly saving = this._saving.asReadonly();
  readonly saveError = this._saveError.asReadonly();
  readonly lastSavedAt = this._lastSavedAt.asReadonly();

  /** True while anything is unsent or in flight - the guard on leaving the route. */
  readonly dirty = computed(() => this._pending() > 0 || this._saving());

  /** The four states of the dirty indicator in the toolbar. */
  readonly status = computed<'saved' | 'unsaved' | 'saving' | 'error'>(() => {
    if (this._saveError()) {
      return 'error';
    }
    if (this._saving()) {
      return 'saving';
    }
    return this._pending() > 0 ? 'unsaved' : 'saved';
  });

  /** Applied to the store when a flush comes back, so ids and audit stamps stay authoritative. */
  onNodesSaved: (nodes: readonly NetworkNode[]) => void = () => undefined;
  onLinksSaved: (links: readonly NetworkLink[]) => void = () => undefined;
  /**
   * Raised when a flush fails, with the failure intact.
   *
   * The editor reads the RFC 7807 `code` off it: a `NETWORK_IMMUTABLE` is answered with the fork
   * prompt rather than a retry, and anything else stays in the banner.
   */
  onFlushFailed: (failure: unknown) => void = () => undefined;

  /** Bind to a network. Clears anything pending from a previous one. */
  configure(networkId: Id): void {
    this.cancelTimer();
    this.positions.clear();
    this.nodePatches.clear();
    this.linkPatches.clear();
    this.networkId = networkId;
    this.destroyed = false;
    this._pending.set(0);
    this._saveError.set(null);
    this._lastSavedAt.set(null);
  }

  queuePosition(nodeId: Id, posX: number, posY: number): void {
    this.positions.set(nodeId, { nodeId, posX, posY });
    this.touch();
  }

  queueNodePatch(nodeId: Id, attributes: NodeAttributes): void {
    const existing = this.nodePatches.get(nodeId);
    this.nodePatches.set(nodeId, { ...(existing ?? { nodeId }), ...attributes, nodeId });
    this.touch();
  }

  queueLinkPatch(linkId: Id, attributes: LinkAttributes): void {
    const existing = this.linkPatches.get(linkId);
    this.linkPatches.set(linkId, { ...(existing ?? { linkId }), ...attributes, linkId });
    this.touch();
  }

  /** Throw away unsent edits. Only for the "discard" branch of a failed save. */
  discardPending(): void {
    this.cancelTimer();
    this.positions.clear();
    this.nodePatches.clear();
    this.linkPatches.clear();
    this._saveError.set(null);
    this.recount();
  }

  /**
   * Send everything pending.
   *
   * Serialised through one chain, so a flush triggered by blur while the timer's flush is still in
   * flight queues behind it instead of racing it. Never rejects: the error is surfaced through
   * {@link saveError} and the entries stay pending, which is what a retry needs.
   */
  flush(): Promise<void> {
    const run = this.chain.then(
      () => this.flushOnce(),
      () => this.flushOnce(),
    );
    this.chain = run;
    return run;
  }

  /** Stop the timer and forget the binding. The component calls this on destroy. */
  destroy(): void {
    this.destroyed = true;
    this.cancelTimer();
  }

  // ------------------------------------------------------------------------ internals

  private touch(): void {
    this.recount();
    if (this.timer === null && !this.destroyed) {
      this.timer = setTimeout(() => {
        this.timer = null;
        void this.flush();
      }, EditorPersistenceService.FLUSH_INTERVAL_MS);
    }
  }

  private cancelTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private recount(): void {
    this._pending.set(this.positions.size + this.nodePatches.size + this.linkPatches.size);
  }

  private async flushOnce(): Promise<void> {
    const networkId = this.networkId;
    if (networkId === null) {
      return;
    }
    this.cancelTimer();

    // Take the pending work out of the maps first. Anything the user does while the requests are in
    // flight lands in the now-empty maps and goes out in the next flush, not this one.
    const positions = [...this.positions.values()];
    const nodePatches = [...this.nodePatches.values()];
    const linkPatches = [...this.linkPatches.values()];
    if (!positions.length && !nodePatches.length && !linkPatches.length) {
      return;
    }
    this.positions.clear();
    this.nodePatches.clear();
    this.linkPatches.clear();
    this.recount();

    this._saving.set(true);
    try {
      for (const batch of chunk(positions, MAX_BULK_BATCH)) {
        const body: BulkNodePositionRequest = { positions: batch };
        const response = await firstValueFrom(
          this.api.patch<WireBulkNodeResponse>(`/networks/${networkId}/nodes/positions`, body),
        );
        this.onNodesSaved(normaliseNodes(response.nodes));
      }
      for (const batch of chunk(nodePatches, MAX_BULK_BATCH)) {
        const body: BulkNodePatchRequest = { nodes: batch };
        const response = await firstValueFrom(
          this.api.patch<WireBulkNodeResponse>(`/networks/${networkId}/nodes`, body),
        );
        this.onNodesSaved(normaliseNodes(response.nodes));
      }
      for (const batch of chunk(linkPatches, MAX_BULK_BATCH)) {
        const body: BulkLinkPatchRequest = { links: batch };
        const response = await firstValueFrom(
          this.api.patch<WireBulkLinkResponse>(`/networks/${networkId}/links`, body),
        );
        this.onLinksSaved(normaliseLinks(response.links));
      }
      this._saveError.set(null);
      this._lastSavedAt.set(Date.now());
    } catch (failure: unknown) {
      // Put the work back so nothing is lost, but never on top of a newer edit to the same element:
      // the user's latest intent wins over the one that failed.
      this.restore(this.positions, positions, (entry) => entry.nodeId);
      this.restore(this.nodePatches, nodePatches, (entry) => entry.nodeId);
      this.restore(this.linkPatches, linkPatches, (entry) => entry.linkId);
      this.recount();
      this._saveError.set(problemMessage(failure, 'Could not save your canvas edits.'));
      this.onFlushFailed(failure);
    } finally {
      this._saving.set(false);
    }
  }

  private restore<T>(target: Map<Id, T>, entries: readonly T[], key: (entry: T) => Id): void {
    for (const entry of entries) {
      const id = key(entry);
      if (!target.has(id)) {
        target.set(id, entry);
      }
    }
  }
}

/** Splits a batch to the `MAX_BULK_BATCH` the bulk endpoints accept in one request. */
function chunk<T>(items: readonly T[], size: number): readonly (readonly T[])[] {
  if (items.length <= size) {
    return items.length ? [items] : [];
  }
  const batches: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    batches.push(items.slice(i, i + size));
  }
  return batches;
}
