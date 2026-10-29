import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { routes } from './app.routes';
import { bearerToken } from './auth';

const keycloak = new Keycloak({ url: 'http://localhost:8180', realm: 'furniflow', clientId: 'furniflow-web' });

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([bearerToken])),
    { provide: Keycloak, useValue: keycloak },
    // Before the first render: finish a sign-in redirect, or pick up an existing Keycloak session in a hidden
    // iframe. Tokens stay in memory, never in localStorage. Authorization code flow with PKCE (keycloak-js default).
    provideAppInitializer(() =>
      keycloak.init({ onLoad: 'check-sso', silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html` }),
    ),
  ],
};
