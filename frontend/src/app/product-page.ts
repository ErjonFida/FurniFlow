import { CurrencyPipe, KeyValuePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
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
      <h1 class="h3">{{ p.name }}</h1>
      <p class="text-body-secondary">{{ p.category }}</p>
      <p>{{ p.description }}</p>
      <dl class="row">
        @for (attribute of p.attributes | keyvalue; track attribute.key) {
          <dt class="col-sm-3">{{ attribute.key }}</dt>
          <dd class="col-sm-9">{{ attribute.value }}</dd>
        }
      </dl>
      <p class="fs-4 fw-semibold mb-1">{{ p.price | currency: 'EUR' }}</p>
      <p>{{ available() ? available() + ' in stock' : 'Out of stock' }}</p>

      <!-- Signed out is fine: the cart is kept in the browser until checkout -->
      @if (!keycloak.authenticated || keycloak.hasRealmRole('customer')) {
        <form class="row g-2 align-items-end" ngNativeValidate (ngSubmit)="add()">
          <div class="col-auto">
            <label class="form-label" for="quantity">Quantity</label>
            <input class="form-control" id="quantity" name="quantity" type="number" min="1" [(ngModel)]="quantity" required />
          </div>
          <div class="col-auto">
            <button class="btn btn-primary" type="submit">Add to cart</button>
          </div>
        </form>
      }
      @if (added()) {
        <p class="mt-3" role="status">Added to your cart. <a routerLink="/cart">Go to cart</a></p>
      }
    } @else if (product.error()) {
      <p role="alert">This product doesn't exist.</p>
    }
  `,
})
export class ProductPage {
  private readonly cart = inject(Cart);
  protected readonly keycloak = inject(Keycloak);

  readonly sku = input.required<string>(); // from the URL
  protected readonly product = httpResource<Product>(() => `/api/catalog/products/${encodeURIComponent(this.sku())}`);
  // Inventory answers 404 for a SKU that was never stocked
  private readonly stock = httpResource<{ available: number }>(() => `/api/inventory/stock/${encodeURIComponent(this.sku())}`);
  protected readonly available = computed(() => (this.stock.hasValue() ? this.stock.value().available : 0));

  quantity = 1;
  protected readonly added = signal(false);

  add() {
    this.cart.add(this.sku(), this.quantity);
    this.added.set(true);
  }
}
