import { Audited, Id } from './common.model';
import { Duration, RoundingPolicy, TimeValidationReport } from './time.model';

/**
 * Network and configuration-variant DTOs - `GET/POST /projects/{id}/networks`,
 * `GET/PUT/DELETE /networks/{id}`, `POST /networks/{id}/clone`,
 * `PUT /networks/{id}/time-base` (FR-01, FR-09, FR-13).
 */

/** One configuration of the supply network: the aggregate over its nodes and links. */
export interface Network extends Audited {
  readonly id: Id;
  readonly projectId: Id;
  /** Unique together with `version` inside the project. */
  readonly name: string;
  /** Variant generation within (project, name): the baseline is 1, each clone takes the next. */
  readonly version: number;
  /** The network the comparison view measures the others against. At most one per project. */
  readonly baseline: boolean;
  /**
   * False once a simulation run has frozen this network. Every mutating call then fails
   * with `NETWORK_IMMUTABLE`, and the editor offers to clone instead of retrying.
   */
  readonly editable: boolean;
  /**
   * Length of one simulation period - the clock every duration in the network is discretised
   * against.
   *
   * Travels with every network response because almost nothing else in the API can be read without
   * it: a lead time of "36 h" only becomes a number of simulation steps beside the period it will
   * be divided by, and the property panel's duration dropdowns default to this unit.
   */
  readonly periodLength: Duration;
  /** How many periods a run over this network covers. */
  readonly horizonPeriods: number;
  /** How a duration that does not divide evenly into the period is rounded. */
  readonly roundingPolicy: RoundingPolicy;
}

/** Client-supplied fields of a network. */
export interface NetworkRequest {
  readonly name: string;
  /** Mark this as the project's baseline. A project may have at most one. Defaults to false. */
  readonly baseline?: boolean;
}

/** Options for forking a network into a configuration variant (FR-09). */
export interface CloneNetworkRequest {
  /** Name for the copy. Omit to reuse the base name, in which case the copy just takes the next version. */
  readonly name?: string;
  /**
   * Structured diff from the base network, stored verbatim in `lever_changes_json`.
   *
   * Deliberately untyped: Phase 2 owns the lever vocabulary and the persistence layer stays
   * agnostic of it. This is what makes lever-level attribution of metric improvements possible.
   */
  readonly leverChanges?: LeverChanges;
}

/** Free-form structured lever diff between a variant and its base network. */
export type LeverChanges = Record<string, unknown>;

/** How a configuration variant came to exist. */
export const VariantOrigin = {
  /** Forked by the user - typically because the editor refused an edit to a simulated network. */
  MANUAL: 'MANUAL',
  /** Persisted by the Phase 2 configuration search as an evaluated candidate. */
  SEARCH: 'SEARCH',
} as const;

export type VariantOrigin = (typeof VariantOrigin)[keyof typeof VariantOrigin];

/**
 * Body of `PUT /networks/{id}/time-base`.
 *
 * All three fields together, because a partial time base is not a thing: changing the period
 * without revisiting the horizon silently changes how much wall-clock time a run covers, and the
 * rounding policy decides whether the change loses any durations at all.
 */
export interface TimeBaseRequest {
  readonly periodLength: Duration;
  readonly horizonPeriods: number;
  readonly roundingPolicy: RoundingPolicy;
}

/**
 * What `PUT /networks/{id}/time-base` returns: the new clock, and what it does to every duration
 * under it.
 *
 * Two things rather than one because changing the period is exactly the moment a 6-hour lead time
 * stops being representable - the editor fills its warning banner from the response that caused it,
 * rather than knowing to ask afterwards.
 */
export interface TimeBaseResponse {
  readonly network: Network;
  readonly validation: TimeValidationReport;
}

/** Links a derived network back to the one it was forked from, with the lever diff. */
export interface ConfigurationVariant extends Audited {
  readonly id: Id;
  readonly projectId: Id;
  /** The network this variant was forked from. */
  readonly baseNetworkId: Id;
  /** The editable copy this variant materialises as. */
  readonly network: Network;
  readonly generatedBy: VariantOrigin;
  readonly leverChanges?: LeverChanges;
}
