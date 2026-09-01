import { TestBed } from '@angular/core/testing';

import { LoginResponse } from './models';
import { TokenStore } from './token-store.service';

/**
 * Smoke test for the JWT store - and for the test toolchain itself.
 *
 * The rule that matters here is the one the guard and the interceptor both depend on: an expired
 * session must not hand out a token, and must not stay behind pretending to be a session.
 */
describe('TokenStore', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  afterEach(() => localStorage.clear());

  function login(expiresAt: string): LoginResponse {
    return {
      token: 'test-token',
      tokenType: 'Bearer',
      expiresInSeconds: 28800,
      expiresAt,
      username: 'researcher',
    };
  }

  it('starts logged out', () => {
    const store = TestBed.inject(TokenStore);

    expect(store.isAuthenticated()).toBe(false);
    expect(store.takeValidToken()).toBeNull();
  });

  it('holds a session after login', () => {
    const store = TestBed.inject(TokenStore);
    store.setSession(login(new Date(Date.now() + 3_600_000).toISOString()));

    expect(store.isAuthenticated()).toBe(true);
    expect(store.username()).toBe('researcher');
    expect(store.takeValidToken()).toBe('test-token');
  });

  it('drops an expired session instead of handing out its token', () => {
    const store = TestBed.inject(TokenStore);
    store.setSession(login(new Date(Date.now() - 1_000).toISOString()));

    expect(store.takeValidToken()).toBeNull();
    expect(store.isAuthenticated()).toBe(false);
  });

  it('forgets the session on logout', () => {
    const store = TestBed.inject(TokenStore);
    store.setSession(login(new Date(Date.now() + 3_600_000).toISOString()));
    store.clear();

    expect(store.isAuthenticated()).toBe(false);
    expect(localStorage.getItem('snrm.auth.session')).toBeNull();
  });
});
