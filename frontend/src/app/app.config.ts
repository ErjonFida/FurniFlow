import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import Keycloak from 'keycloak-js';
import { routes } from './app.routes';
import { bearerToken, checkSignIn } from './auth';

const keycloak = new Keycloak({ url: 'http://localhost:8180', realm: 'furniflow', clientId: 'furniflow-web' });

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()), // :sku in the URL becomes the page's sku input
    provideHttpClient(withInterceptors([bearerToken])),
    { provide: Keycloak, useValue: keycloak },
    provideAppInitializer(() => checkSignIn(keycloak)),
  ],
};
