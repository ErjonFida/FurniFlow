import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, RouterStateSnapshot, UrlTree } from '@angular/router';
import Keycloak from 'keycloak-js';
import { bearerToken, checkSignIn, hasRole } from './auth';

describe('checkSignIn', () => {
  const keycloak = (answer: Promise<boolean>) => ({ init: () => answer }) as unknown as Keycloak;

  it('waits for Keycloak when it answers', async () => {
    expect(await checkSignIn(keycloak(Promise.resolve(true)))).toBe(true);
  });

  it('starts the shop signed out when Keycloak fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    await checkSignIn(keycloak(Promise.reject(new Error('Keycloak is down'))));

    expect(warn).toHaveBeenCalledOnce();
    warn.mockRestore();
  });

  it("stops waiting for a Keycloak that never answers", async () => {
    await checkSignIn(keycloak(new Promise(() => undefined)), 1); // would time the test out if it kept waiting
  });
});

describe('auth', () => {
  beforeEach(() => checkSignIn({ init: () => Promise.resolve(false) } as unknown as Keycloak)); // Keycloak has answered

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
    isTokenExpired: () => false,
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

  describe('when the token cannot be refreshed', () => {
    const refreshFails = (): Partial<Keycloak> => ({ ...signedIn([]), updateToken: () => Promise.reject(new Error('Keycloak is down')) });

    async function tokenOnCall(keycloak: Partial<Keycloak>) {
      setUp(keycloak);
      TestBed.inject(HttpClient).get('/api/catalog/products').subscribe();
      await Promise.resolve(); // updateToken() is async
      return TestBed.inject(HttpTestingController).expectOne('/api/catalog/products').request.headers.get('Authorization');
    }

    it('still sends the call, with the token while it is valid', async () => {
      expect(await tokenOnCall(refreshFails())).toBe('Bearer access-token');
    });

    it('leaves an expired token off, because the services would refuse it even on public pages', async () => {
      expect(await tokenOnCall({ ...refreshFails(), isTokenExpired: () => true })).toBeNull();
    });

    it('leaves the token off when the session has ended', async () => {
      const keycloak: Partial<Keycloak> = {
        ...refreshFails(),
        // As the real one does: it only answers for a signed-in user
        isTokenExpired: () => {
          throw new Error('Not authenticated');
        },
      };
      keycloak.updateToken = () => {
        keycloak.authenticated = false;
        return Promise.reject(new Error('Session not active'));
      };
      expect(await tokenOnCall(keycloak)).toBeNull();
    });
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

  it('lets admins in and sends everyone else home', async () => {
    setUp(signedIn(['admin']));
    expect(await guard('admin')).toBe(true);

    TestBed.resetTestingModule();
    setUp(signedIn(['customer']));
    expect(((await guard('admin')) as UrlTree).toString()).toBe('/');
  });

  it('waits for a sign-in that is still completing, instead of sending the user to Keycloak again', async () => {
    let finish!: () => void;
    const keycloak: Partial<Keycloak> = { authenticated: false, login: vi.fn(() => Promise.resolve()), hasRealmRole: () => true };
    keycloak.init = () =>
      new Promise((resolve) => {
        finish = () => resolve((keycloak.authenticated = true));
      });
    setUp(keycloak);
    await checkSignIn(keycloak as Keycloak, 1); // the shop renders before Keycloak has finished

    const allowed = guard('admin');
    finish();

    expect(await allowed).toBe(true);
    expect(keycloak.login).not.toHaveBeenCalled();
  });
});
