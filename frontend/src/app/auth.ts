import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { from, switchMap } from 'rxjs';

// Adds the user's access token to our own API calls only, so it never leaks to another host.
// The token is refreshed first if it expires within 30 seconds.
export const bearerToken: HttpInterceptorFn = (request, next) => {
  const keycloak = inject(Keycloak);
  if (!keycloak.authenticated || !request.url.startsWith('/api/')) {
    return next(request);
  }
  return from(keycloak.updateToken(30)).pipe(
    switchMap(() => next(request.clone({ setHeaders: { Authorization: `Bearer ${keycloak.token}` } }))),
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
