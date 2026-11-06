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
            <p>{{ available() ? available() + ' in stock' : 'Out of stock' }}</p>

            <!-- Signed out is fine: the cart is kept in the browser until checkout -->
            @if (!keycloak.authenticated || keycloak.hasRealmRole('customer')) {
              <form class="vstack gap-3" ngNativeValidate (ngSubmit)="add()">
                <div>
                  <label class="form-label" for="quantity">Quantity</label>
                  <input class="form-control" id="quantity" name="quantity" type="number" min="1" [(ngModel)]="quantity" required />
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
