import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { catchError, from, of, switchMap } from 'rxjs';

// Before the first render: finish a sign-in redirect, or pick up an existing Keycloak session in a hidden iframe.
// Browsing needs no sign-in, so the shop starts signed out if Keycloak fails or is slow (init() alone has no time
// limit on the iframe). An answer that comes after the wait still signs the user in.
export const checkSignIn = (keycloak: Keycloak, waitMs = 3000) => {
  // The session ended (it lapsed, or the user signed out in another tab). Pages only read the signed-in state when
  // they draw, so start over.
  keycloak.onAuthLogout = () => location.reload();
  return Promise.race([
    keycloak
      .init({ onLoad: 'check-sso', silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html` })
      .catch((error) => console.warn('Keycloak is unavailable, so the shop starts signed out', error)),
    new Promise((resolve) => setTimeout(resolve, waitMs)),
  ]);
};

// Adds the access token to our own API calls only, so it never leaks to another host, refreshing it first if it
// expires within 30 seconds. If that fails the call still goes out, without the expired token: browsing needs no
// sign-in, and the services answer 401 to an expired token even on public pages.
export const bearerToken: HttpInterceptorFn = (request, next) => {
  const keycloak = inject(Keycloak);
  if (!keycloak.authenticated || !request.url.startsWith('/api/')) {
    return next(request);
  }
  return from(keycloak.updateToken(30)).pipe(
    catchError(() => of(false)),
    switchMap(() =>
      next(
        keycloak.authenticated && !keycloak.isTokenExpired()
          ? request.clone({ setHeaders: { Authorization: `Bearer ${keycloak.token}` } })
          : request,
      ),
    ),
  );
};

// Only decides what the UI shows: the services check the token's roles again on every request.
export function hasRole(role: string): CanActivateFn {
  return (_route, state) => {
    const keycloak = inject(Keycloak);
    if (!keycloak.authenticated) {
      return keycloak.login({ redirectUri: location.origin + state.url }).then(() => false);
    }
    return keycloak.hasRealmRole(role) || inject(Router).parseUrl('/');
  };
}
