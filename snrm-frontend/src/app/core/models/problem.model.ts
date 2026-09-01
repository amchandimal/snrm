import { Id } from './common.model';

/**
 * RFC 7807 `application/problem+json` - the single error shape of the API.
 *
 * The backend's `GlobalExceptionHandler` returns Spring's `ProblemDetail` and adds a machine
 * readable `code` extension to it, plus per-case extensions such as `fieldErrors`. Anything the
 * frontend shows a user should come from `detail` (human sentence) while branching logic keys off
 * `code` - never off the sentence.
 */
export interface ProblemDetail {
  /** Problem type URI. Spring defaults to `about:blank`. */
  readonly type?: string;
  /** Short, human-readable summary, e.g. `Not found`. */
  readonly title?: string;
  /** HTTP status code, repeated in the body. */
  readonly status?: number;
  /** Human-readable explanation specific to this occurrence. Safe to show to the user. */
  readonly detail?: string;
  /** URI reference identifying the specific occurrence. */
  readonly instance?: string;

  /** Machine-readable error code extension added by the backend. See {@link ProblemCode}. */
  readonly code?: string;

  /** Bean-validation failures, keyed by property path (e.g. `nodes[2].posX`). */
  readonly fieldErrors?: Readonly<Record<string, string>>;

  /** Constraint violations on path/query parameters, as `path: message` strings. */
  readonly violations?: readonly string[];

  /** Present on `NETWORK_IMMUTABLE`: the network that refused the edit. */
  readonly networkId?: Id;

  /** Further extensions the backend may add; read with bracket notation. */
  readonly [extension: string]: unknown;
}

/**
 * Error codes the backend attaches as the `code` extension.
 *
 * Kept as a const object rather than a TypeScript `enum` so it erases to plain strings on the wire
 * and stays comparable against any future code the backend adds.
 */
export const ProblemCode = {
  /** 400 - request body failed bean validation; see `fieldErrors`. */
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  /** 400 - malformed request the handler could not bind. */
  BAD_REQUEST: 'BAD_REQUEST',
  /** 401 - unknown user or wrong password on `POST /auth/login`. */
  INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  /** 404 - no such entity for this user. */
  NOT_FOUND: 'NOT_FOUND',
  /** 409 - a name already taken within its uniqueness scope. */
  DUPLICATE_NAME: 'DUPLICATE_NAME',
  /** 409 - a database constraint rejected the write. */
  CONSTRAINT_VIOLATION: 'CONSTRAINT_VIOLATION',
  /**
   * 409 - the network has completed or in-flight simulation runs and is frozen.
   * The editor answers this by offering to fork a configuration variant, or to discard the runs
   * and edit in place (FR-20) - never by retrying.
   */
  NETWORK_IMMUTABLE: 'NETWORK_IMMUTABLE',
  /**
   * 409 - a run cannot be deleted because a job still owns it: it is `QUEUED` or `RUNNING`
   * (FR-20). Cancel the job first (`DELETE /jobs/{jobId}`), then delete.
   *
   * The whole-network form (`DELETE /networks/{id}/runs`) answers this too, and when it does
   * **nothing was deleted** - a half-discard would destroy the completed results and leave the
   * network frozen anyway. A client must not retry per run on seeing it.
   */
  RUN_ACTIVE: 'RUN_ACTIVE',
  /** 409 - a run named in `?runIds=` is not `DONE`, so it has nothing to put in a column (FR-17). */
  RUN_NOT_DONE: 'RUN_NOT_DONE',
} as const;

export type ProblemCode = (typeof ProblemCode)[keyof typeof ProblemCode];
