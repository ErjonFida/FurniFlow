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
});
