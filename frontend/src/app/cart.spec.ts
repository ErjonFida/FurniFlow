import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { Cart, CartPage } from './cart';

describe('CartPage', () => {
  beforeEach(() => localStorage.clear());

  it('orders the whole cart, again with the same Idempotency-Key, so a retry never places a second order', () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { authenticated: true } },
      ],
    });
    const cart = TestBed.inject(Cart);
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
  });
});
