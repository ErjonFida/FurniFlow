import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { Cart, CartPage } from './cart';

describe('CartPage', () => {
  let cart: Cart;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { authenticated: true } },
      ],
    });
    cart = TestBed.inject(Cart);
  });

  it('keeps what another tab put in the cart when this tab adds to it', () => {
    // A write to localStorage is announced to every other tab, never to the tab that wrote it
    const otherTabWrites = (key: string, newValue: string | null) =>
      window.dispatchEvent(new StorageEvent('storage', { key, newValue }));

    cart.add('SOFA-1', 1);
    otherTabWrites('cart', '{"SOFA-1":1,"CHAIR-1":2}');
    cart.add('TABLE-1', 1);
    expect(cart.items()).toEqual({ 'SOFA-1': 1, 'CHAIR-1': 2, 'TABLE-1': 1 });

    otherTabWrites('kc-callback-1', '{"state":"signing in"}'); // Keycloak's sign-in uses localStorage too
    expect(cart.count()).toBe(4);

    otherTabWrites('cart', null); // the entry was removed
    expect(cart.items()).toEqual({});
  });

  it('orders the whole cart, with the same Idempotency-Key until the cart changes, so a retry never places a second order', () => {
    cart.add('SOFA-1', 1);
    cart.add('CHAIR-1', 4);
    cart.add('SOFA-1', 1); // the same product twice is one line
    const fixture = TestBed.createComponent(CartPage);
    const server = TestBed.inject(HttpTestingController);

    fixture.componentInstance.order();
    const first = server.expectOne('/api/orders');
    first.flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.componentInstance.order();
    const retry = server.expectOne('/api/orders');

    expect(first.request.body).toEqual({ items: [{ sku: 'SOFA-1', quantity: 2 }, { sku: 'CHAIR-1', quantity: 4 }] });
    expect(first.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(first.request.headers.get('Idempotency-Key'));

    // Say the first order was saved but its answer got lost. A changed cart is a new order, so it needs its own key:
    // with the old one, order-service would refuse it.
    retry.flush(null, { status: 504, statusText: 'Gateway Timeout' });
    cart.add('TABLE-1', 1);
    fixture.componentInstance.order();
    const changed = server.expectOne('/api/orders');
    expect(changed.request.body.items).toContainEqual({ sku: 'TABLE-1', quantity: 1 });
    expect(changed.request.headers.get('Idempotency-Key')).not.toBe(first.request.headers.get('Idempotency-Key'));
  });

  it('changes a quantity, and puts the old one back when the new one is not a whole number from 1 up', () => {
    cart.add('SOFA-1', 2);
    const fixture = TestBed.createComponent(CartPage);
    fixture.detectChanges();
    const quantity = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=number]')!;
    const type = (value: string) => {
      quantity.value = value;
      quantity.dispatchEvent(new Event('change'));
    };

    type('3');
    expect(cart.items()).toEqual({ 'SOFA-1': 3 });
    for (const wrong of ['', '0', '1.5']) {
      type(wrong);
      expect(cart.items()).toEqual({ 'SOFA-1': 3 });
      expect(quantity.value).toBe('3');
    }
  });
});
