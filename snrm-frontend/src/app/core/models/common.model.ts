/**
 * Shared primitives for the DTO contract.
 *
 * The backend exchanges JSON DTOs only - never JPA entities - and Jackson is configured
 * to write dates as ISO-8601 strings rather than epoch numbers, so timestamps arrive as strings.
 */

/**
 * An ISO-8601 instant in UTC, e.g. `2026-07-25T22:14:07Z`.
 *
 * Kept as a string rather than a `Date`: it is exactly what the wire carries, it survives
 * round-tripping through a project export bundle unchanged, and parsing is the caller's decision.
 */
export type IsoInstant = string;

/** Surrogate key. Every SNRM aggregate uses a `bigint` primary key. */
export type Id = number;

/** Audit timestamps carried by every persisted entity. */
export interface Audited {
  readonly createdAt: IsoInstant;
  readonly updatedAt: IsoInstant;
}
