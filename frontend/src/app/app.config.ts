import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import Keycloak from 'keycloak-js';
import { routes } from './app.routes';
import { bearerToken, checkSignIn } from './auth';

// Where Keycloak is comes from public/keycloak.json (localhost:8180), read during init. A deploy swaps that file for
// its own, so the same image signs in against any Keycloak.
const keycloak = new Keycloak('/keycloak.json');

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(), // :sku in the URL becomes the page's sku input
      // A new page starts at the top. Without this the router leaves the scroll position alone, so opening a product
      // from far down the shop opened it scrolled down too. Back returns to the old position only if the page is that
      // long by then, so the shop, whose products arrive a moment later, starts at the top again.
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    provideHttpClient(withInterceptors([bearerToken])),
    { provide: Keycloak, useValue: keycloak },
    provideAppInitializer(() => checkSignIn(keycloak)),
  ],
};
