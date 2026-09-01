import { IsoInstant } from './common.model';

/**
 * Authentication DTOs - `POST /api/v1/auth/login`.
 *
 * Phase 1 has exactly one research account, so there is no registration, no roles and no
 * refresh token: a login returns a bearer JWT with a fixed lifetime and the user logs in again when
 * it expires.
 */

/** Credentials posted to `/auth/login`. */
export interface LoginRequest {
  readonly username: string;
  readonly password: string;
}

/** A freshly issued bearer token. */
export interface LoginResponse {
  /** Signed JWT. Sent as `Authorization: Bearer <token>` on every other `/api/v1` call. */
  readonly token: string;
  /** Authentication scheme; always `Bearer`. */
  readonly tokenType: string;
  /** Lifetime from now, in seconds - what a client timer needs. */
  readonly expiresInSeconds: number;
  /** Absolute expiry (UTC) - what a human reading the response needs. */
  readonly expiresAt: IsoInstant;
  /** The authenticated user, echoed back. */
  readonly username: string;
}
