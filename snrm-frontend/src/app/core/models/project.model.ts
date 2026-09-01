import { Audited, Id } from './common.model';

/**
 * Project DTOs - `GET/POST /projects`, `GET/PUT/DELETE /projects/{id}` (FR-01).
 *
 * A project is the top-level container for one modelling exercise: its networks, products,
 * scenarios and configuration variants.
 */

/** A project as the API returns it. */
export interface Project extends Audited {
  readonly id: Id;
  /** Unique among this user's projects. Max 160 characters. */
  readonly name: string;
  /** The research user who owns it. Constant while Phase 1 has one account. */
  readonly ownerId: Id;
}

/**
 * Client-supplied fields of a project, for create and rename.
 *
 * The owner is deliberately absent: the backend reads it from the bearer token, so the API has no
 * way to express "somebody else's project".
 */
export interface ProjectRequest {
  readonly name: string;
}
