import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import Keycloak from 'keycloak-js';
import { Cart } from './cart';
import { ProductPage } from './product-page';

describe('ProductPage', () => {
  it('starts afresh when moving straight to another product, because the router keeps the same page', async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'products/:sku', component: ProductPage }], withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { authenticated: false } },
      ],
    });
    const server = TestBed.inject(HttpTestingController);
    const cart = TestBed.inject(Cart);
    const harness = await RouterTestingHarness.create();
    const open = async (sku: string) => {
      await harness.navigateByUrl(`/products/${sku}`);
      TestBed.tick(); // the page asks for the product once its sku is set
      server.expectOne(`/api/catalog/products/${sku}`).flush({ sku, name: sku, category: 'Sofas', price: 1, attributes: {} });
      server.expectOne(`/api/inventory/stock/${sku}`).flush({ available: 5 });
      await harness.fixture.whenStable();
    };
    const quantity = () => harness.routeNativeElement!.querySelector<HTMLInputElement>('#quantity')!;
    const addToCart = async () => {
      harness.routeNativeElement!.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
      await harness.fixture.whenStable();
    };

    await open('SOFA-1');
    quantity().value = '3';
    quantity().dispatchEvent(new Event('input'));
    await addToCart();
    expect(cart.items()).toEqual({ 'SOFA-1': 3 });
    expect(harness.routeNativeElement!.textContent).toContain('Added to your cart');

    await open('CHAIR-1');
    expect(harness.routeNativeElement!.textContent).not.toContain('Added to your cart');
    expect(quantity().value).toBe('1');

    await addToCart();
    expect(cart.items()).toEqual({ 'SOFA-1': 3, 'CHAIR-1': 1 });
  });
});
