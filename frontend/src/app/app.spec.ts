import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { App } from './app';

describe('App', () => {
  function render(keycloak: Partial<Keycloak>) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: Keycloak, useValue: keycloak }],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return fixture;
  }

  it('offers to sign in or register when signed out', () => {
    const page = render({ authenticated: false }).nativeElement as HTMLElement;
    expect(page.querySelector('.brand')?.textContent).toContain('FurniFlow');
    expect(page.textContent).toContain('Sign in');
    expect(page.textContent).toContain('Register');
    expect(page.querySelector('a[href="/cart"]')).not.toBeNull(); // shopping before signing in
  });

  it('shows the user and, for admins, the admin page', () => {
    const page = render({
      authenticated: true,
      tokenParsed: { preferred_username: 'ada' },
      hasRealmRole: (role: string) => role === 'admin',
    }).nativeElement as HTMLElement;
    expect(page.textContent).toContain('ada');
    expect(page.querySelector('a[href="/admin"]')).not.toBeNull();
    expect(page.querySelector('a[href="/orders"]')).toBeNull(); // admins don't shop
    expect(page.querySelector('a[href="/cart"]')).toBeNull();
  });

  describe('the Furniture menu', () => {
    const mouse = (type: string) => Object.assign(new Event(type), { pointerType: 'mouse' });
    let page: HTMLElement;
    let button: Element;
    let open: boolean;

    beforeEach(() => {
      vi.useFakeTimers();
      page = render({ authenticated: false }).nativeElement as HTMLElement;
      button = page.querySelector('[popovertarget=products-menu]')!;
      open = false;
      // The test browser doesn't have popovers
      Object.assign(page.querySelector('#products-menu')!, {
        matches: () => open,
        showPopover: () => (open = true),
        hidePopover: () => (open = false),
      });
    });

    afterEach(() => vi.useRealTimers());

    it('opens when the mouse points at the button, and closes a moment after it leaves', () => {
      button.dispatchEvent(mouse('pointerenter'));
      expect(open).toBe(true);

      button.dispatchEvent(mouse('pointerleave'));
      expect(open).toBe(true);
      vi.advanceTimersByTime(200);
      expect(open).toBe(false);
    });

    it('stays open while the mouse crosses from the button to the menu', () => {
      button.dispatchEvent(mouse('pointerenter'));
      button.dispatchEvent(mouse('pointerleave'));
      vi.advanceTimersByTime(100);
      page.querySelector('#products-menu')!.dispatchEvent(mouse('pointerenter'));
      vi.advanceTimersByTime(1000);
      expect(open).toBe(true);
    });

    it('leaves a touch to the tap, which has no hover', () => {
      button.dispatchEvent(Object.assign(new Event('pointerenter'), { pointerType: 'touch' }));
      expect(open).toBe(false);
    });
  });

  it('lists the rooms in the menu, and shows what is in the one you point at', async () => {
    const fixture = render({ authenticated: false });
    TestBed.inject(HttpTestingController)
      .expectOne('/api/catalog/products')
      .flush([
        { sku: 'A', name: 'Oak table', category: 'Tables', price: 1, attributes: {} },
        { sku: 'B', name: 'Oslo sofa', category: 'Sofas', price: 1, attributes: {} },
        { sku: 'C', name: 'Pine table', category: 'Tables', price: 1, attributes: {} },
      ]);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const rooms = [...page.querySelectorAll('.menu-rooms a')];
    expect(rooms.map((a) => [a.textContent?.trim(), a.getAttribute('href')])).toEqual([
      ['Sofas', '/products?category=Sofas'],
      ['Tables', '/products?category=Tables'],
    ]);
    expect(page.querySelector('.menu-empty')).not.toBeNull();

    rooms[1].dispatchEvent(new Event('mouseenter'));
    await fixture.whenStable();

    expect(page.querySelector('.menu-room h2')?.textContent).toBe('Tables');
    expect([...page.querySelectorAll('.menu-room li a')].map((a) => a.getAttribute('href'))).toEqual(['/products/A', '/products/C']);
  });
});
