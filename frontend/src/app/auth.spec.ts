import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, RouterStateSnapshot, UrlTree } from '@angular/router';
import Keycloak from 'keycloak-js';
import { bearerToken, hasRole } from './auth';

describe('auth', () => {
  function setUp(keycloak: Partial<Keycloak>) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([bearerToken])),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: keycloak },
      ],
    });
  }

  const signedIn = (roles: string[]): Partial<Keycloak> => ({
    authenticated: true,
    token: 'access-token',
    updateToken: () => Promise.resolve(false),
    hasRealmRole: (role: string) => roles.includes(role),
  });

  it('sends the token to our API and nowhere else', async () => {
    setUp(signedIn([]));
    const http = TestBed.inject(HttpClient);
    const server = TestBed.inject(HttpTestingController);

    http.get('/api/orders/1').subscribe();
    http.get('https://cdn.example.com/sofa.jpg').subscribe();

    await Promise.resolve(); // updateToken() is async
    expect(server.expectOne('/api/orders/1').request.headers.get('Authorization')).toBe('Bearer access-token');
    expect(server.expectOne('https://cdn.example.com/sofa.jpg').request.headers.has('Authorization')).toBe(false);
  });

  function guard(role: string) {
    const target = { url: '/admin' } as RouterStateSnapshot;
    return TestBed.runInInjectionContext(() => hasRole(role)({} as ActivatedRouteSnapshot, target));
  }

  it('sends signed-out users to Keycloak, then back to the page they wanted', async () => {
    const login = vi.fn(() => Promise.resolve());
    setUp({ authenticated: false, login });

    expect(await guard('admin')).toBe(false);
    expect(login).toHaveBeenCalledWith({ redirectUri: `${location.origin}/admin` });
  });

  it('lets admins in and sends everyone else home', () => {
    setUp(signedIn(['admin']));
    expect(guard('admin')).toBe(true);

    TestBed.resetTestingModule();
    setUp(signedIn(['customer']));
    expect((guard('admin') as UrlTree).toString()).toBe('/');
  });
});
