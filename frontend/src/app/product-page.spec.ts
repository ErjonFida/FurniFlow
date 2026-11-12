import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import Keycloak from 'keycloak-js';
import { Cart } from './cart';
import { ProductPage } from './product-page';

describe('ProductPage', () => {
  let server: HttpTestingController;
  let cart: Cart;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'products/:sku', component: ProductPage }], withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { authenticated: false } },
      ],
    });
    server = TestBed.inject(HttpTestingController);
    cart = TestBed.inject(Cart);
    harness = await RouterTestingHarness.create();
  });

  // Answers the product; the stock call is left to the test
  const open = async (sku: string, error?: { status: number; statusText: string }) => {
    await harness.navigateByUrl(`/products/${sku}`);
    TestBed.tick(); // the page asks for the product once its sku is set
    server
      .expectOne(`/api/catalog/products/${sku}`)
      .flush(error ? null : { sku, name: sku, category: 'Sofas', price: 1, attributes: {} }, error);
    // Not whenStable: that waits for the stock call too. The resource takes its value in a promise, so let those run first.
    await new Promise((resolve) => setTimeout(resolve));
    TestBed.tick();
  };
  const stock = async (sku: string, body: object | null, error?: { status: number; statusText: string }) => {
    server.expectOne(`/api/inventory/stock/${sku}`).flush(body, error);
    await harness.fixture.whenStable();
  };
  const text = () => harness.routeNativeElement!.textContent;
  const quantity = () => harness.routeNativeElement!.querySelector<HTMLInputElement>('#quantity');
  const setQuantity = (value: string) => {
    quantity()!.value = value;
    quantity()!.dispatchEvent(new Event('input'));
  };
  const addToCart = async () => {
    harness.routeNativeElement!.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await harness.fixture.whenStable();
  };

  it('starts afresh when moving straight to another product, because the router keeps the same page', async () => {
    await open('SOFA-1');
    await stock('SOFA-1', { available: 5 });
    setQuantity('3');
    await addToCart();
    expect(cart.items()).toEqual({ 'SOFA-1': 3 });
    expect(text()).toContain('Added to your cart');

    await open('CHAIR-1');
    await stock('CHAIR-1', { available: 5 });
    expect(text()).not.toContain('Added to your cart');
    expect(quantity()!.value).toBe('1');

    await addToCart();
    expect(cart.items()).toEqual({ 'SOFA-1': 3, 'CHAIR-1': 1 });
  });

  it("says nothing about stock until it's known, and sells only what there is", async () => {
    await open('SOFA-1');
    expect(text()).toContain('Add to cart');
    expect(text()).not.toContain('Out of stock');

    await stock('SOFA-1', { available: 2 });
    expect(text()).toContain('2 in stock');
    setQuantity('3');
    await addToCart();
    expect(cart.items()).toEqual({});

    await open('CHAIR-1');
    await stock('CHAIR-1', { available: 0 });
    expect(text()).toContain('Out of stock');
    expect(quantity()).toBeNull();
  });

  it("says a product doesn't exist only when the catalog says so", async () => {
    await open('GONE-1', { status: 404, statusText: 'Not Found' });
    expect(text()).toContain("doesn't exist");

    await open('SOFA-1', { status: 503, statusText: 'Service Unavailable' });
    expect(text()).not.toContain("doesn't exist");
    expect(text()).toContain("couldn't be loaded");
  });

  it('calls a product inventory never stocked out of stock, but not one inventory just failed to answer for', async () => {
    await open('SOFA-1');
    await stock('SOFA-1', null, { status: 404, statusText: 'Not Found' });
    expect(text()).toContain('Out of stock');
    expect(quantity()).toBeNull();

    await open('CHAIR-1');
    await stock('CHAIR-1', null, { status: 503, statusText: 'Service Unavailable' });
    expect(text()).not.toContain('Out of stock');
    await addToCart();
    expect(cart.items()).toEqual({ 'CHAIR-1': 1 });
  });
});
