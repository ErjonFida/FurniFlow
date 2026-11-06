import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { catchError, from, of, switchMap } from 'rxjs';

// Before the first render: finish a sign-in redirect, or pick up an existing Keycloak session in a hidden iframe.
// Tokens stay in memory, never in localStorage. Authorization code flow with PKCE (keycloak-js default).
// Browsing the shop needs no sign-in, so if Keycloak fails or is slow the shop starts signed out. An answer that comes
// after the wait still signs the user in. It can't be left to init() alone: it has no time limit on the hidden iframe.
export const checkSignIn = (keycloak: Keycloak, waitMs = 3000) => {
  // The session ended (it lapsed, or the user signed out in another tab). Pages only read the signed-in state when
  // they draw, so start over: public pages then show as signed out, and pages that need a role go to sign-in.
  keycloak.onAuthLogout = () => location.reload();
  return Promise.race([
    keycloak
      .init({ onLoad: 'check-sso', silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html` })
      .catch((error) => console.warn('Keycloak is unavailable, so the shop starts signed out', error)),
    new Promise((resolve) => setTimeout(resolve, waitMs)),
  ]);
};

// Adds the user's access token to our own API calls only, so it never leaks to another host.
// The token is refreshed first if it expires within 30 seconds. If that fails (Keycloak is down, say) the call still
// goes out, because browsing needs no sign-in. An expired token stays off it: the services answer 401 to one even on
// public pages. A page that needs a sign-in gets its 401 from the service.
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
