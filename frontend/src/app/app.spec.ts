import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { App } from './app';

describe('App', () => {
  function render(keycloak: Partial<Keycloak>) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), { provide: Keycloak, useValue: keycloak }],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('offers to sign in when signed out', () => {
    const page = render({ authenticated: false });
    expect(page.querySelector('.navbar-brand')?.textContent).toContain('FurniFlow');
    expect(page.textContent).toContain('Sign in');
  });

  it('shows the user and, for admins, the admin page', () => {
    const page = render({
      authenticated: true,
      tokenParsed: { preferred_username: 'ada' },
      hasRealmRole: (role: string) => role === 'admin',
    });
    expect(page.textContent).toContain('ada');
    expect(page.querySelector('a[href="/admin"]')).not.toBeNull();
    expect(page.querySelector('a[href="/orders"]')).toBeNull(); // admins don't shop
  });
});
