import { Audited, Id } from './common.model';
import { Duration, Rate } from './time.model';

/**
 * Node DTOs - CRUD `/networks/{id}/nodes`, `/nodes/{id}`, and the bulk canvas endpoints
 * `PATCH /networks/{id}/nodes` and `PATCH /networks/{id}/nodes/positions`.
 *
 * Named `NetworkNode` rather than `Node` so it never collides with the DOM's global `Node`.
 *
 * `capacity` and `processingTime` carry their own unit (FR-13): a node whose capacity was
 * set as 500 per hour must not read back as 12,000 per day. What the engine will do with them is a
 * different question, answered by `GET /networks/{id}/time-validation`.
 */

/** The echelon a node occupies. */
export const NodeType = {
  /** Upstream source of material; has no inbound links. */
  SUPPLIER: 'SUPPLIER',
  /** Production site; transforms inbound material into outbound product. */
  PLANT: 'PLANT',
  /** Distribution centre; stocks and forwards, may transship laterally. */
  DC: 'DC',
  /** Demand sink; carries a `demand` rate per product and has no outbound links. */
  CUSTOMER: 'CUSTOMER',
} as const;

export type NodeType = (typeof NodeType)[keyof typeof NodeType];

/** Every node type in echelon order - palette order in the editor. */
export const NODE_TYPES: readonly NodeType[] = [
  NodeType.SUPPLIER,
  NodeType.PLANT,
  NodeType.DC,
  NodeType.CUSTOMER,
];

/** A facility in the network. */
export interface NetworkNode extends Audited {
  readonly id: Id;
  readonly networkId: Id;
  /** Unique within the network; import resolves link endpoints by this name. */
  readonly name: string;
  readonly type: NodeType;
  /** Throughput ceiling over its own unit. A null `value` inside means unconstrained. */
  readonly capacity: Rate;
  /** Dwell before material can leave - cycle time at a plant, cross-dock at a DC. */
  readonly processingTime: Duration;
  readonly fixedCost: number;
  readonly varCost: number;
  /** Independent per-period failure probability, in [0,1]. */
  readonly failureProb: number;
  /** Geographic tag REGION-scoped disruption events resolve through. */
  readonly region: string | null;
  readonly lat: number | null;
  readonly lng: number | null;
  /** Editor canvas coordinate - layout, not geography. Persisted so a manual layout survives reload. */
  readonly posX: number | null;
  readonly posY: number | null;
  /**
   * Short annotation drawn beneath the node's name in smaller, quieter type (FR-30).
   *
   * Null when there is none, and **never the empty string**: the backend trims a caption on every
   * write path and stores a blank one as null (`com.snrm.network.Captions`), so the canvas asks "is
   * there a caption" rather than "is the caption empty".
   */
  readonly caption: string | null;
  /** Whether the canvas draws it. An empty caption draws nothing whatever this says. */
  readonly captionVisible: boolean;
}

/**
 * Complete client-supplied state of a node: create, and full replacement on PUT.
 *
 * An omitted nullable field is *cleared* on PUT - which is how a value is unset, since the bulk
 * PATCH cannot express it. For the two unit-bearing fields that means unconstrained and zero
 * respectively, stated in the network's period unit, so a caller sends everything it wants to keep.
 */
export interface NodeRequest {
  readonly name: string;
  readonly type: NodeType;
  /** Omit for unconstrained. */
  readonly capacity?: Rate;
  /** Omit for no dwell. */
  readonly processingTime?: Duration;
  readonly fixedCost?: number;
  readonly varCost?: number;
  /** In [0,1]. */
  readonly failureProb?: number;
  readonly region?: string | null;
  readonly lat?: number | null;
  readonly lng?: number | null;
  readonly posX?: number | null;
  readonly posY?: number | null;
  /** Omitted - or blank - clears it (FR-30). */
  readonly caption?: string | null;
  /**
   * Omitting this means **visible**, not hidden - the one field on this request whose absence is
   * not "cleared" (FR-30, `NOT NULL DEFAULT TRUE` in `V10__element_captions.sql`).
   */
  readonly captionVisible?: boolean;
}

/**
 * Changed attributes of one node. Omitted fields are left unchanged; a PATCH cannot clear a
 * nullable field - use PUT for that.
 *
 * A unit-bearing field is sent **whole**: a rate's unit cannot change without restating its value,
 * and there is nothing sensible to do with half of the pair.
 *
 * **{@link caption} is the one exception to "a PATCH cannot clear a nullable field", and it is
 * deliberate (FR-30).** A *present but empty* caption clears it; omitting it still means unchanged.
 * A caption is an ordinary edit written through this endpoint and the editor has to be
 * able to remove one, so either this DTO gives the empty string a meaning or the single caption edit
 * that removes something goes through `PUT /nodes/{id}` and replaces every other attribute of the
 * node on the way. `com.snrm.network.Captions` is the backend's statement of the same rule.
 */
export interface NodePatch {
  readonly nodeId: Id;
  readonly name?: string;
  readonly type?: NodeType;
  readonly capacity?: Rate;
  readonly processingTime?: Duration;
  readonly fixedCost?: number;
  readonly varCost?: number;
  readonly failureProb?: number;
  readonly region?: string;
  readonly lat?: number;
  readonly lng?: number;
  readonly posX?: number;
  readonly posY?: number;
  /** Omit to leave alone; send `''` to **clear** - see the note above (FR-30). */
  readonly caption?: string;
  readonly captionVisible?: boolean;
}

/** New canvas coordinates for one node. */
export interface NodePositionPatch {
  readonly nodeId: Id;
  readonly posX: number;
  readonly posY: number;
}

/** A batch of node attribute edits from the canvas editor. At most {@link MAX_BULK_BATCH} entries. */
export interface BulkNodePatchRequest {
  readonly nodes: readonly NodePatch[];
}

/** A batch of node moves from the canvas editor. At most {@link MAX_BULK_BATCH} entries. */
export interface BulkNodePositionRequest {
  readonly positions: readonly NodePositionPatch[];
}

/** The nodes a batched edit wrote, as they now stand. */
export interface BulkNodeResponse {
  readonly updated: number;
  readonly nodes: readonly NetworkNode[];
}

/**
 * Largest batch the bulk endpoints accept in one request.
 *
 * Sized against the 1,000-node networks of FR-04, so a whole `cytoscape-dagre` auto-layout fits in
 * one call. Mirrors `BulkNodePositionRequest.MAX_BATCH` on the backend.
 */
export const MAX_BULK_BATCH = 1000;
