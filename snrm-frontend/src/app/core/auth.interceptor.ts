import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { TokenStore } from './token-store.service';

/** The one call that must not carry a token - it is how a token is obtained. */
const LOGIN_PATH = '/auth/login';

/**
 * Attaches the JWT to every API request and reacts to rejection.
 *
 * Functional interceptor, registered in `app.config.ts` via `withInterceptors`.
 *
 * Two responsibilities, both of which have to live in one place or they drift:
 *
 *  - **Outbound** - `Authorization: Bearer <token>` on every `/api/v1` request except the login
 *    itself. Requests to anything else (assets, future third-party URLs) are left untouched, so a
 *    token is never leaked off-origin.
 *  - **Inbound** - a 401 means the token is gone, expired or was signed with a key the backend no
 *    longer has (it regenerates one per restart when `SNRM_JWT_SECRET` is unset). The session is
 *    dropped and the user is sent to the login page with a return address, rather than every view
 *    inventing its own recovery.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const tokens = inject(TokenStore);
  const router = inject(Router);

  const isLogin = request.url.endsWith(LOGIN_PATH);
  const isApiCall = request.url.includes('/api/');
  const token = isApiCall && !isLogin ? tokens.takeValidToken() : null;

  const outbound = token
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;

  return next(outbound).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !isLogin) {
        tokens.clear();
        const redirectTo = router.url;
        void router.navigate(['/login'], {
          queryParams: redirectTo && redirectTo !== '/login' ? { redirectTo } : {},
        });
      }
      return throwError(() => error);
    }),
  );
};
