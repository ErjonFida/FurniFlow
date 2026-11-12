import { CurrencyPipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, httpResource } from '@angular/common/http';
import { Component, computed, effect, inject, Injectable, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import Keycloak from 'keycloak-js';
import { Product } from './products';

// SKU → quantity. Kept in localStorage, so it survives a reload and signing in (a full page redirect to Keycloak).
@Injectable({ providedIn: 'root' })
export class Cart {
  readonly items = signal<Record<string, number>>(JSON.parse(localStorage.getItem('cart') ?? '{}'));
  readonly count = computed(() => Object.values(this.items()).reduce((sum, quantity) => sum + quantity, 0));

  constructor() {
    effect(() => localStorage.setItem('cart', JSON.stringify(this.items())));
    // Another tab changed the cart. Without this, the next change here would save over it. The effect above then saves
    // the same value again, which browsers don't announce, so the tabs don't keep setting each other off.
    window.addEventListener('storage', (event) => {
      if (event.key === 'cart') {
        this.items.set(JSON.parse(event.newValue ?? '{}'));
      }
    });
  }

  add(sku: string, quantity: number) {
    this.set(sku, (this.items()[sku] ?? 0) + quantity);
  }

  set(sku: string, quantity: number) {
    this.items.update((items) => ({ ...items, [sku]: quantity }));
  }

  remove(sku: string) {
    this.items.update((items) => Object.fromEntries(Object.entries(items).filter(([s]) => s !== sku)));
  }

  // Saved at once, not when the effect gets to it: signing out leaves the page straight away
  clear() {
    this.items.set({});
    localStorage.removeItem('cart');
  }
}

// order-service gets only SKUs and quantities and looks the prices up itself, so a price can't be made up here
@Component({
  selector: 'app-cart-page',
  imports: [CurrencyPipe, RouterLink],
  template: `
    <h1>Cart</h1>
    @if (lines().length) {
      <table class="table align-middle">
        <thead>
          <tr><th>Product</th><th class="text-end">Price</th><th class="text-end">Quantity</th><th></th></tr>
        </thead>
        <tbody>
          @for (line of lines(); track line.sku) {
            <tr>
              <td><a [routerLink]="['/products', line.sku]">{{ line.product?.name ?? line.sku }}</a></td>
              <td class="text-end">{{ line.product?.price | currency: 'EUR' }}</td>
              <td class="text-end">
                <input #quantity class="form-control form-control-sm d-inline-block" style="width: 5rem" type="number" min="1" required
                  [attr.aria-label]="'Quantity of ' + (line.product?.name ?? line.sku)" [value]="line.quantity"
                  (change)="setQuantity(line.sku, quantity)" />
              </td>
              <td class="text-end"><button class="btn btn-link btn-sm" type="button" (click)="cart.remove(line.sku)">Remove</button></td>
            </tr>
          }
        </tbody>
        <tfoot>
          <tr><th>Total</th><th class="text-end">{{ total() | currency: 'EUR' }}</th><th colspan="2"></th></tr>
        </tfoot>
      </table>
      @if (keycloak.authenticated) {
        <button class="btn btn-primary" type="button" (click)="order()">Order</button>
      } @else {
        <button class="btn btn-primary" type="button" (click)="keycloak.login()">Sign in to order</button>
      }
    } @else {
      <p>Your cart is empty. <a routerLink="/products">Browse furniture</a></p>
    }
    @if (message()) {
      <p class="mt-3 text-danger" role="alert">{{ message() }}</p>
    }
  `,
})
export class CartPage {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly keycloak = inject(Keycloak);
  protected readonly cart = inject(Cart);

  //loads the whole catalog to price a few lines; ask catalog-service for just these SKUs once it pages
  private readonly products = httpResource<Product[]>(() => '/api/catalog/products');
  protected readonly lines = computed(() => {
    const catalog = new Map(this.products.hasValue() ? this.products.value().map((p) => [p.sku, p] as const) : []);
    return Object.entries(this.cart.items()).map(([sku, quantity]) => ({ sku, quantity, product: catalog.get(sku) }));
  });
  protected readonly total = computed(() =>
    this.lines().reduce((sum, line) => sum + (line.product?.price ?? 0) * line.quantity, 0),
  );
  protected readonly message = signal('');
  // One key per cart: if the answer gets lost and the customer clicks again, order-service returns the order it
  // already placed. A changed cart is another order, so it gets a new key: a reused key with other items is refused.
  private readonly idempotencyKey = computed(() => {
    this.cart.items();
    return crypto.randomUUID();
  });

  // On change, not on every keystroke: clearing the field to type a new number mustn't count. Anything but a whole
  // number from 1 up (min and the default step decide) shows the cart's quantity again; Remove takes a line out.
  protected setQuantity(sku: string, input: HTMLInputElement) {
    if (input.validity.valid) {
      this.cart.set(sku, input.valueAsNumber);
    } else {
      input.value = String(this.cart.items()[sku]);
    }
  }

  order() {
    const items = Object.entries(this.cart.items()).map(([sku, quantity]) => ({ sku, quantity }));
    this.http.post('/api/orders', { items }, { headers: { 'Idempotency-Key': this.idempotencyKey() } }).subscribe({
      next: () => {
        this.cart.clear();
        this.router.navigate(['/orders']);
      },
      error: (e: HttpErrorResponse) => this.message.set(e.error?.detail ?? e.message),
    });
  }
}
