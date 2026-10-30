import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { ProductPage } from './product-page';

describe('ProductPage', () => {
  it('orders again with the same Idempotency-Key, so a retry never places a second order', () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { authenticated: true, hasRealmRole: () => true } },
      ],
    });
    const fixture = TestBed.createComponent(ProductPage);
    fixture.componentRef.setInput('sku', 'SOFA-1');
    const server = TestBed.inject(HttpTestingController);

    fixture.componentInstance.order();
    const first = server.expectOne('/api/orders');
    first.flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.componentInstance.order();
    const retry = server.expectOne('/api/orders');

    expect(first.request.body).toEqual({ sku: 'SOFA-1', quantity: 1 });
    expect(first.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(first.request.headers.get('Idempotency-Key'));
  });
});
