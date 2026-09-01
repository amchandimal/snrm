import { Audited, Id } from './common.model';
import { Rate } from './time.model';

/**
 * Product and node–product DTOs - CRUD `/projects/{id}/products`, `/products/{id}`,
 * `/networks/{id}/products`, `/nodes/{id}/products/{productId}` (FR-01).
 */

/**
 * A product flowing through the network.
 *
 * Project-scoped, so every configuration variant of a project shares one catalogue.
 */
export interface Product extends Audited {
  readonly id: Id;
  readonly projectId: Id;
  /** Unique within the project. */
  readonly name: string;
  /** Value of one unit; weights unserved demand when service loss is expressed in money. */
  readonly unitValue: number;
}

/** Client-supplied fields of a product. */
export interface ProductRequest {
  readonly name: string;
  readonly unitValue?: number;
}

/**
 * Demand, inventory and holding cost for one product at one node.
 *
 * `demand` and `holdingCost` are rates over their own unit, not per-period quantities: a
 * demand of 120 a week reads back as 120 a week whether the network steps in days or in weeks.
 * `initialInventory` and `safetyStock` stay bare numbers - a stock level is a quantity at an
 * instant and has no time dimension to state.
 */
export interface NodeProduct extends Audited {
  readonly nodeId: Id;
  readonly productId: Id;
  /** The product's name, denormalised for display. */
  readonly productName: string;
  /** Demand over its own unit. Meaningful on CUSTOMER nodes. */
  readonly demand: Rate;
  /** Opening stock; acts as an additional supply source in the period after it is stocked. */
  readonly initialInventory: number;
  /** Target buffer. One of the Phase 2 redundancy levers. */
  readonly safetyStock: number;
  /** Cost of carrying one unit, over the unit of time it is charged for. */
  readonly holdingCost: Rate;
}

/**
 * Client-supplied node–product figures for `PUT /nodes/{id}/products/{productId}`.
 *
 * The endpoint is an upsert - `(node, product)` is the natural key, so the URL names the row
 * completely - and a full replacement: an omitted rate becomes zero in the network's period unit,
 * so a caller sends every field it wants to keep.
 */
export interface NodeProductRequest {
  readonly demand?: Rate;
  readonly initialInventory?: number;
  readonly safetyStock?: number;
  readonly holdingCost?: Rate;
}
