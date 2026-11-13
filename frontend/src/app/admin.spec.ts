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
  const unavailable = { status: 503, statusText: 'Service Unavailable' };

  function render() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { authServerUrl: 'http://keycloak', realm: 'furniflow' } },
      ],
    });
    const fixture = TestBed.createComponent(Admin);
    TestBed.tick(); // the page asks for products, stock and orders
    return { fixture, server: TestBed.inject(HttpTestingController) };
  }

  it("doesn't show stock as 0 while inventory isn't answering", async () => {
    const { fixture, server } = render();
    server.expectOne('/api/catalog/products').flush([{ sku: 'SOFA-1', name: 'Sofa', category: 'Sofas', price: 1, attributes: {} }]);
    server.expectOne('/api/inventory/stock').flush(null, unavailable);
    server.expectOne('/api/orders/all').flush([]);
    await fixture.whenStable();

    const stockCell = fixture.nativeElement.querySelectorAll('tbody td')[3];
    expect(stockCell.textContent.trim()).toBe('?');
  });

  it("says so when products or orders can't be loaded, instead of leaving the tables out", async () => {
    const { fixture, server } = render();
    server.expectOne('/api/catalog/products').flush(null, unavailable);
    server.expectOne('/api/inventory/stock').flush([]);
    server.expectOne('/api/orders/all').flush(null, unavailable);
    await fixture.whenStable();

    const alerts = [...fixture.nativeElement.querySelectorAll('[role=alert]')].map((alert) => alert.textContent.trim());
    expect(alerts).toEqual(["Products can't be loaded right now.", "Orders can't be loaded right now."]);
  });
});
