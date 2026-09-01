import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';

import { ApiService } from './api.service';
import { LoginRequest, LoginResponse } from './models';
import { TokenStore } from './token-store.service';

/**
 * Login and logout for the single research user.
 *
 * The only endpoint in the API that needs no token is the one this calls; everything after it is
 * carried by the interceptor. There is no refresh token by design - Phase 1 issues an 8-hour token
 * and the researcher logs in again.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiService);
  private readonly tokens = inject(TokenStore);
  private readonly router = inject(Router);

  /** Signals re-exported so views need only one injection. */
  readonly isAuthenticated = this.tokens.isAuthenticated;
  readonly username = this.tokens.username;

  /** `POST /auth/login`. Records the session on success; errors propagate as RFC 7807 problems. */
  login(credentials: LoginRequest): Observable<LoginResponse> {
    return this.api
      .post<LoginResponse>('/auth/login', credentials)
      .pipe(tap((response) => this.tokens.setSession(response)));
  }

  /** Drops the session and returns to the login page. Stateless API: nothing to tell the server. */
  logout(): void {
    this.tokens.clear();
    void this.router.navigate(['/login']);
  }
}
