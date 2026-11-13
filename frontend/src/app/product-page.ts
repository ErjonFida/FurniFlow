import { CurrencyPipe, KeyValuePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component, computed, inject, input, linkedSignal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import Keycloak from 'keycloak-js';
import { Cart } from './cart';
import { Product } from './products';

@Component({
  selector: 'app-product-page',
  imports: [CurrencyPipe, FormsModule, KeyValuePipe, RouterLink],
  template: `
    @if (product.hasValue()) {
      @let p = product.value();
      <nav class="breadcrumbs" aria-label="Breadcrumb">
        <a routerLink="/">Home</a> /
        <a routerLink="/products" [queryParams]="{ category: p.category }">{{ p.category }}</a> /
        {{ p.name }}
      </nav>
      <div class="row g-4">
        <div class="col-lg-5">
          @if (p.imageUrl) {
            <img class="product-photo product-photo-wide" [src]="p.imageUrl" [alt]="p.name" />
          } @else {
            <span class="product-photo product-photo-wide" aria-hidden="true">{{ p.name[0] }}</span>
          }
        </div>
        <div class="col-lg-4">
          <h1>{{ p.name }}</h1>
          <p class="fs-5">{{ p.description }}</p>
          <dl class="row">
            @for (attribute of p.attributes | keyvalue; track attribute.key) {
              <dt class="col-sm-4">{{ attribute.key }}</dt>
              <dd class="col-sm-8">{{ attribute.value }}</dd>
            }
          </dl>
        </div>
        <div class="col-lg-3">
          <div class="buy-box">
            <p class="buy-box-price">{{ p.price | currency: 'EUR' }}</p>
            @if (available() !== undefined) {
              <p>{{ available() ? available() + ' in stock' : 'Out of stock' }}</p>
            }

            <!-- Signed out is fine: the cart is kept in the browser until checkout. So is unknown stock: checkout checks again -->
            @if (available() !== 0 && (!keycloak.authenticated || keycloak.hasRealmRole('customer'))) {
              <form class="vstack gap-3" ngNativeValidate (ngSubmit)="add()">
                <div>
                  <label class="form-label" for="quantity">Quantity</label>
                  <input class="form-control" id="quantity" name="quantity" type="number" min="1" [attr.max]="available()"
                    [(ngModel)]="quantity" required />
                </div>
                <button class="btn btn-primary" type="submit">Add to cart</button>
              </form>
            }
            @if (added()) {
              <p class="mt-3 mb-0" role="status">Added to your cart. <a routerLink="/cart">Go to cart</a></p>
            }
          </div>
        </div>
      </div>
    } @else if (product.error()) {
      <p role="alert">
        {{ product.statusCode() === 404 ? "This product doesn't exist." : "This product couldn't be loaded. Try again in a moment." }}
      </p>
    }
  `,
})
export class ProductPage {
  private readonly cart = inject(Cart);
  protected readonly keycloak = inject(Keycloak);

  readonly sku = input.required<string>(); // from the URL
  protected readonly product = httpResource<Product>(() => `/api/catalog/products/${encodeURIComponent(this.sku())}`);
  private readonly stock = httpResource<{ available: number }>(() => `/api/inventory/stock/${encodeURIComponent(this.sku())}`);
  protected readonly available = computed(() => {
    if (this.stock.hasValue()) {
      return this.stock.value().available;
    }
    // Inventory answers 404 for a SKU that was never stocked. Anything else (still loading, inventory down) is unknown,
    // and "Out of stock" would be false then.
    return this.stock.error() && this.stock.statusCode() === 404 ? 0 : undefined;
  });

  // The router keeps this page when moving straight to another product, so these start afresh with each sku
  protected readonly quantity = linkedSignal({ source: this.sku, computation: () => 1 });
  protected readonly added = linkedSignal({ source: this.sku, computation: () => false });

  add() {
    this.cart.add(this.sku(), this.quantity());
    this.added.set(true);
  }
}
