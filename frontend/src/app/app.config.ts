import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import Keycloak from 'keycloak-js';
import { routes } from './app.routes';
import { bearerToken, checkSignIn } from './auth';

// Keycloak's address comes from public/keycloak.json, so a deploy can swap that file and reuse the same image
const keycloak = new Keycloak('/keycloak.json');

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(), // :sku in the URL becomes the page's sku input
      // A new page starts at the top. Back returns to the old position only if the page is that long by then, so the
      // shop, whose products arrive a moment later, starts at the top again.
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    provideHttpClient(withInterceptors([bearerToken])),
    { provide: Keycloak, useValue: keycloak },
    provideAppInitializer(() => checkSignIn(keycloak)),
  ],
};
