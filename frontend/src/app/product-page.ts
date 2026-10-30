import { CurrencyPipe, KeyValuePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, httpResource } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { Product } from './products';

@Component({
  selector: 'app-product-page',
  imports: [CurrencyPipe, FormsModule, KeyValuePipe],
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

      @if (!keycloak.authenticated) {
        <button class="btn btn-primary" type="button" (click)="keycloak.login()">Sign in to order</button>
      } @else if (keycloak.hasRealmRole('customer')) {
        <form class="row g-2 align-items-end" ngNativeValidate (ngSubmit)="order()">
          <div class="col-auto">
            <label class="form-label" for="quantity">Quantity</label>
            <input class="form-control" id="quantity" name="quantity" type="number" min="1" [(ngModel)]="quantity" required />
          </div>
          <div class="col-auto">
            <button class="btn btn-primary" type="submit">Order</button>
          </div>
        </form>
      }
      @if (message()) {
        <p class="mt-3 text-danger" role="alert">{{ message() }}</p>
      }
    } @else if (product.error()) {
      <p role="alert">This product doesn't exist.</p>
    }
  `,
})
export class ProductPage {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly keycloak = inject(Keycloak);

  readonly sku = input.required<string>(); // from the URL
  protected readonly product = httpResource<Product>(() => `/api/catalog/products/${encodeURIComponent(this.sku())}`);
  // Inventory answers 404 for a SKU that was never stocked
  private readonly stock = httpResource<{ available: number }>(() => `/api/inventory/stock/${encodeURIComponent(this.sku())}`);
  protected readonly available = computed(() => (this.stock.hasValue() ? this.stock.value().available : 0));

  quantity = 1;
  protected readonly message = signal('');
  // One key per visit to this page. If the answer gets lost and the customer clicks again, the same key goes
  // with it and order-service returns the order it already placed instead of reserving the units twice.
  private readonly idempotencyKey = crypto.randomUUID();

  order() {
    this.http
      .post('/api/orders', { sku: this.sku(), quantity: this.quantity }, { headers: { 'Idempotency-Key': this.idempotencyKey } })
      .subscribe({
        next: () => this.router.navigate(['/orders']),
        error: (e: HttpErrorResponse) => this.message.set(e.error?.detail ?? e.message),
      });
  }
}
