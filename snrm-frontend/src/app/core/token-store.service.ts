import { Injectable, computed, signal } from '@angular/core';

import { LoginResponse } from './models';

/** What the SPA keeps about the logged-in research user. */
export interface AuthSession {
  readonly token: string;
  readonly username: string;
  /** Absolute expiry (UTC, ISO-8601) as issued by `POST /auth/login`. */
  readonly expiresAt: string;
}

const STORAGE_KEY = 'snrm.auth.session';

/**
 * The JWT store - the one place a bearer token lives.
 *
 * State is a signal, so the shell, the guard and every view react to login and logout without an
 * event bus (signal-based state, no NgRx). The session is mirrored into `localStorage`
 * so a page reload does not log the researcher out mid-experiment; this is a
 * single-user research prototype, where that trade is the right one.
 *
 * Expiry is checked when the token is *used*, not by a timer: signals must stay pure, and a clock
 * read inside a `computed` would cache a value that silently goes stale.
 */
@Injectable({ providedIn: 'root' })
export class TokenStore {
  private readonly _session = signal<AuthSession | null>(readStoredSession());

  /** The current session, or null when logged out. */
  readonly session = this._session.asReadonly();

  /** True while a session is held. Drives the shell chrome; the guard uses {@link takeValidToken}. */
  readonly isAuthenticated = computed(() => this._session() !== null);

  readonly username = computed(() => this._session()?.username ?? null);

  /** Absolute expiry of the held session, or null. */
  readonly expiresAt = computed(() => this._session()?.expiresAt ?? null);

  /** Records the session returned by a successful login. */
  setSession(login: LoginResponse): void {
    const session: AuthSession = {
      token: login.token,
      username: login.username,
      expiresAt: login.expiresAt,
    };
    this._session.set(session);
    writeStoredSession(session);
  }

  /** Drops the session - logout, a 401 from the API, or an expired token. */
  clear(): void {
    this._session.set(null);
    writeStoredSession(null);
  }

  /**
   * The bearer token if it is still valid, otherwise null.
   *
   * An expired session is cleared here rather than left to rot: the next thing that happens after
   * this returns null is a redirect to the login page, and the shell should already show the user
   * as logged out by then.
   */
  takeValidToken(): string | null {
    const session = this._session();
    if (!session) {
      return null;
    }
    if (isExpired(session)) {
      this.clear();
      return null;
    }
    return session.token;
  }
}

function isExpired(session: AuthSession): boolean {
  const expiry = Date.parse(session.expiresAt);
  return Number.isNaN(expiry) || expiry <= Date.now();
}

function readStoredSession(): AuthSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (!isAuthSession(parsed) || isExpired(parsed)) {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return parsed;
  } catch {
    // Unreadable or unavailable storage is not worth failing a bootstrap over: start logged out.
    return null;
  }
}

function writeStoredSession(session: AuthSession | null): void {
  try {
    if (session) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Storage can be denied (private mode, disabled cookies). The in-memory signal still works.
  }
}

function isAuthSession(value: unknown): value is AuthSession {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<AuthSession>;
  return (
    typeof candidate.token === 'string' &&
    typeof candidate.username === 'string' &&
    typeof candidate.expiresAt === 'string'
  );
}
