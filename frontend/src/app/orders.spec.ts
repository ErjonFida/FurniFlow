import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Orders } from './orders';

describe('Orders', () => {
  it("says so when the orders can't be loaded, instead of showing only the heading", async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(Orders);
    TestBed.tick(); // the page asks for the orders

    TestBed.inject(HttpTestingController).expectOne('/api/orders').flush(null, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent.trim()).toBe("Your orders can't be loaded right now.");
  });

  it('offers Pay only while order-service says the order is held, and drops an error once a payment goes through', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(Orders);
    const server = TestBed.inject(HttpTestingController);
    const page = fixture.nativeElement as HTMLElement;
    // This browser's clock is no judge: the first hold is long past by it, the second still running
    const order = (id: string, status: string, reservedUntil: string) => ({ id, status, reservedUntil, items: [], total: 1 });
    TestBed.tick();
    server.expectOne('/api/orders').flush([order('held', 'RESERVED', '2000-01-01T00:00:00Z'), order('late', 'EXPIRED', '2999-01-01T00:00:00Z')]);
    await fixture.whenStable();
    const pay = [...page.querySelectorAll('button')].filter((button) => button.textContent === 'Pay');
    expect(pay.length).toBe(1);

    pay[0].click();
    server.expectOne('/api/orders/held/pay').flush({ detail: 'The payment failed.' }, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    expect(page.textContent).toContain('The payment failed.');

    pay[0].click();
    server.expectOne('/api/orders/held/pay').flush({});
    TestBed.tick(); // the page asks for the orders again
    server.expectOne('/api/orders').flush([]);
    await fixture.whenStable();
    expect(page.textContent).not.toContain('The payment failed.');
  });
});
