import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { Admin, parseAttributes } from './admin';

describe('parseAttributes', () => {
  it('reads one "name: value" per line and skips the rest', () => {
    expect(parseAttributes('seats: 3\nfabric:linen \n\nno colon here\nsize: 200 x 90: cm')).toEqual({
      seats: '3',
      fabric: 'linen',
      size: '200 x 90: cm',
    });
  });
});

describe('Admin', () => {
  it("doesn't show stock as 0 while inventory isn't answering", async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { authServerUrl: 'http://keycloak', realm: 'furniflow' } },
      ],
    });
    const server = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Admin);
    TestBed.tick(); // the page asks for products, stock and orders

    server.expectOne('/api/catalog/products').flush([{ sku: 'SOFA-1', name: 'Sofa', category: 'Sofas', price: 1, attributes: {} }]);
    server.expectOne('/api/inventory/stock').flush(null, { status: 503, statusText: 'Service Unavailable' });
    server.expectOne('/api/orders/all').flush([]);
    await fixture.whenStable();

    const stockCell = fixture.nativeElement.querySelectorAll('tbody td')[3];
    expect(stockCell.textContent.trim()).toBe('?');
  });
});
