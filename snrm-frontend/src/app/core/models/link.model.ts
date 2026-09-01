import { Audited, Id } from './common.model';
import { Duration, Rate } from './time.model';

/**
 * Link DTOs - CRUD `/networks/{id}/links`, `/links/{id}`, and the bulk canvas endpoint
 * `PATCH /networks/{id}/links`.
 *
 * `leadTime` and `capacity` carry their own unit (FR-13). They come back in the unit they
 * were entered in and are never restated into periods: the canvas labels an arc "6 h" because that
 * is what the user said, and how many simulation steps that becomes is a property of the network's
 * clock rather than of the arc.
 */

/** A directed transport arc between two nodes of the same network. */
export interface NetworkLink extends Audited {
  readonly id: Id;
  readonly networkId: Id;
  readonly sourceNodeId: Id;
  readonly targetNodeId: Id;
  /** Transit time in its own unit; flow arrives that much later as pipeline inventory. */
  readonly leadTime: Duration;
  /** Throughput ceiling over its own unit. A null `value` inside means unconstrained. */
  readonly capacity: Rate;
  readonly unitCost: number;
  /** Independent per-period failure probability, in [0,1]. */
  readonly failureProb: number;
  /**
   * Short annotation drawn beneath the arc's existing label - its declared lead time (FR-30).
   * Null when there is none, and never the empty string; see `NetworkNode.caption`.
   */
  readonly caption: string | null;
  /** Whether the canvas draws it. An empty caption draws nothing whatever this says. */
  readonly captionVisible: boolean;
}

/**
 * A new directed arc and its attributes. Both endpoints must belong to the network in the path.
 *
 * An omitted `leadTime` means zero transit and an omitted `capacity` unconstrained, both stated in
 * the network's period unit - enough for the canvas to create a link from a mouse gesture and let
 * the user fill in the panel afterwards.
 */
export interface LinkRequest {
  readonly sourceNodeId: Id;
  readonly targetNodeId: Id;
  readonly leadTime?: Duration;
  readonly capacity?: Rate;
  readonly unitCost?: number;
  readonly failureProb?: number;
  /** Omitted - or blank - means no caption (FR-30). */
  readonly caption?: string | null;
  /** Omitting this means **visible**, not hidden (FR-30). */
  readonly captionVisible?: boolean;
}

/**
 * Full replacement of a link's attributes.
 *
 * Endpoints cannot be changed - delete and redraw instead, which is also the gesture the canvas
 * offers. As a PUT, an omitted `capacity` clears it back to unconstrained, an omitted
 * `leadTime` goes to zero transit and an omitted `caption` is cleared, so a caller sends every field
 * it wants to keep - including the caption, which is why `NetworkEditorStore.clearLinkCapacity`
 * carries the pair it is not editing.
 */
export interface LinkAttributesRequest {
  readonly leadTime?: Duration;
  readonly capacity?: Rate;
  readonly unitCost?: number;
  readonly failureProb?: number;
  /** Omitted - or blank - clears it (FR-30). */
  readonly caption?: string | null;
  /** Omitting this means **visible**, not hidden (FR-30). */
  readonly captionVisible?: boolean;
}

/**
 * Changed attributes of one link. Omitted fields are left unchanged.
 *
 * A unit-bearing field is sent **whole**: there is no way to change a rate's unit without restating
 * its value, and nothing sensible to do with half of the pair.
 *
 * {@link caption} carries the single exception `NodePatch.caption` documents: a present-but-empty
 * caption **clears** it (FR-30).
 */
export interface LinkPatch {
  readonly linkId: Id;
  readonly leadTime?: Duration;
  readonly capacity?: Rate;
  readonly unitCost?: number;
  readonly failureProb?: number;
  /** Omit to leave alone; send `''` to **clear** (FR-30). */
  readonly caption?: string;
  readonly captionVisible?: boolean;
}

/** A batch of link attribute edits from the canvas editor. */
export interface BulkLinkPatchRequest {
  readonly links: readonly LinkPatch[];
}

/** The links a batched edit wrote, as they now stand. */
export interface BulkLinkResponse {
  readonly updated: number;
  readonly links: readonly NetworkLink[];
}
