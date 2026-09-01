import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { TokenStore } from './token-store.service';

/**
 * Keeps unauthenticated users out of the application.
 *
 * Guards the route tree, not the data: the API rejects a tokenless call on its own, and the
 * interceptor handles that. What this adds is not showing a researcher an empty projects table when
 * the real answer is "you are logged out" - and remembering where they were headed, so the login
 * form can put them back there.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const tokens = inject(TokenStore);
  const router = inject(Router);

  if (tokens.takeValidToken()) {
    return true;
  }

  return router.createUrlTree(['/login'], { queryParams: { redirectTo: state.url } });
};
