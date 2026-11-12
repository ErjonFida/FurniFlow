import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, httpResource } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import Keycloak from 'keycloak-js';
import { switchMap } from 'rxjs';
import { isHeld, Order } from './orders';
import { catalogResource, Product } from './products';

// One "seats: 3" per line becomes { seats: '3' }
export const parseAttributes = (text: string): Record<string, string> =>
  Object.fromEntries([...text.matchAll(/^(.+?):(.*)$/gm)].map(([, name, value]) => [name.trim(), value.trim()]));

@Component({
  selector: 'app-admin',
  imports: [CurrencyPipe, DatePipe, FormsModule, RouterLink],
  template: `
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h1 class="mb-0">Admin</h1>
      <a class="btn btn-outline-secondary btn-sm" [href]="usersUrl" target="_blank">Users and roles</a>
    </div>
    <div class="row g-4">
      <section class="col-lg-6">
        <h2 class="h5">Products</h2>
        @if (products.hasValue()) {
          <table class="table table-sm align-middle">
            <thead>
              <tr><th>SKU</th><th>Name</th><th class="text-end">Price</th><th class="text-end">In stock</th><th></th></tr>
            </thead>
            <tbody>
              @for (p of products.value(); track p.sku) {
                <tr>
                  <td>{{ p.sku }}</td>
                  <td>{{ p.name }}</td>
                  <td class="text-end">{{ p.price | currency: 'EUR' }}</td>
                  <td class="text-end">{{ available().get(p.sku) ?? 0 }}</td>
                  <td class="text-end text-nowrap">
                    <button class="btn btn-link btn-sm" type="button" (click)="edit(p)">Edit</button>
                    <button class="btn btn-link btn-sm text-danger" type="button" (click)="remove(p)">Delete</button>
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="5">No products yet.</td></tr>
              }
            </tbody>
          </table>
        }
      </section>

      <section class="col-lg-6">
        <h2 class="h5">Product</h2>
        <form class="vstack gap-2" ngNativeValidate (ngSubmit)="save()">
          <div>
            <label class="form-label" for="product-sku">SKU</label>
            <input class="form-control" id="product-sku" name="product-sku" [(ngModel)]="product.sku" required />
          </div>
          <div>
            <label class="form-label" for="name">Name</label>
            <input class="form-control" id="name" name="name" [(ngModel)]="product.name" required />
          </div>
          <div>
            <label class="form-label" for="category">Category</label>
            <input class="form-control" id="category" name="category" [(ngModel)]="product.category" required />
          </div>
          <div>
            <label class="form-label" for="price">Price (EUR)</label>
            <input class="form-control" id="price" name="price" type="number" min="0.01" step="0.01" [(ngModel)]="product.price" required />
          </div>
          <div>
            <label class="form-label" for="image">Image address</label>
            <input class="form-control" id="image" name="image" type="url" pattern="https?://.*" [(ngModel)]="product.imageUrl" />
          </div>
          <div>
            <label class="form-label" for="description">Description</label>
            <textarea class="form-control" id="description" name="description" rows="2" [(ngModel)]="product.description"></textarea>
          </div>
          <div>
            <label class="form-label" for="attributes">Attributes, one "name: value" per line</label>
            <textarea class="form-control font-monospace" id="attributes" name="attributes" rows="4" [(ngModel)]="attributes"></textarea>
          </div>
          <div>
            <button class="btn btn-primary" type="submit">Save</button>
          </div>
        </form>

        <h2 class="h5 mt-4">Stock</h2>
        <form class="row g-2 align-items-end" ngNativeValidate (ngSubmit)="restock()">
          <div class="col-auto">
            <label class="form-label" for="sku">SKU</label>
            <input class="form-control" id="sku" name="sku" [(ngModel)]="sku" required />
          </div>
          <div class="col-auto">
            <label class="form-label" for="quantity">Units to add</label>
            <input class="form-control" id="quantity" name="quantity" type="number" min="1" [(ngModel)]="quantity" required />
          </div>
          <div class="col-auto">
            <button class="btn btn-primary" type="submit">Restock</button>
          </div>
        </form>
      </section>
    </div>
    @if (message()) {
      <p class="mt-3" role="status">{{ message() }}</p>
    }

    <section class="mt-4">
      <h2 class="h5">Orders</h2>
      @if (orders.hasValue()) {
        <table class="table table-sm align-middle">
          <thead>
            <tr><th>Customer</th><th>Items</th><th class="text-end">Total</th><th>Status</th></tr>
          </thead>
          <tbody>
            @for (order of orders.value(); track order.id) {
              <tr>
                <td>{{ order.customer }}</td>
                <td>
                  @for (item of order.items; track $index) {
                    <div>{{ item.quantity }} × <a [routerLink]="['/products', item.sku]">{{ item.name ?? item.sku }}</a></div>
                  }
                </td>
                <td class="text-end">{{ order.total | currency: 'EUR' }}</td>
                @if (order.status === 'PAID') {
                  <td>Paid</td>
                } @else if (order.status === 'CANCELLED') {
                  <td>Cancelled</td>
                } @else if (isHeld(order)) {
                  <td>Reserved until {{ order.reservedUntil | date: 'short' }}</td>
                } @else {
                  <td>Hold expired</td>
                }
              </tr>
            } @empty {
              <tr><td colspan="4">No orders yet.</td></tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
})
export class Admin {
  private readonly http = inject(HttpClient);
  private readonly keycloak = inject(Keycloak);
  protected readonly products = catalogResource();
  protected readonly stock = httpResource<{ sku: string; available: number }[]>(() => '/api/inventory/stock');
  protected readonly orders = httpResource<Order[]>(() => '/api/orders/all');
  protected readonly isHeld = isHeld;
  protected readonly available = computed(
    () => new Map(this.stock.hasValue() ? this.stock.value().map((s) => [s.sku, s.available] as const) : []),
  );
  // Keycloak's admin console for this realm. The admin role may manage its users and their roles there, nothing else.
  protected readonly usersUrl = `${this.keycloak.authServerUrl}/admin/${this.keycloak.realm}/console/#/${this.keycloak.realm}/users`;
  product = { sku: '', name: '', category: '', price: null as number | null, description: '', imageUrl: '' };
  attributes = '';
  sku = '';
  quantity = 1;
  readonly message = signal('');
  private readonly fail = (e: HttpErrorResponse) => this.message.set(e.error?.detail ?? e.message);

  edit(p: Product) {
    this.product = {
      sku: p.sku,
      name: p.name,
      category: p.category,
      price: p.price,
      description: p.description ?? '',
      imageUrl: p.imageUrl ?? '',
    };
    this.attributes = Object.entries(p.attributes).map(([name, value]) => `${name}: ${value}`).join('\n');
    this.sku = p.sku; // restock the product being edited
  }

  save() {
    const { sku, imageUrl, ...product } = this.product;
    this.http
      .put(`/api/catalog/products/${encodeURIComponent(sku.trim())}`, {
        ...product,
        imageUrl: imageUrl || null, // an empty field means no image, not an address that's empty
        attributes: parseAttributes(this.attributes),
      })
      .subscribe({
        next: () => {
          this.products.reload();
          this.message.set(`${sku} saved`);
        },
        error: this.fail,
      });
  }

  remove(p: Product) {
    if (!confirm(`Delete ${p.name}? Its stock and past orders stay.`)) {
      return;
    }
    this.http.delete(`/api/catalog/products/${encodeURIComponent(p.sku)}`).subscribe({
      next: () => {
        this.products.reload();
        this.message.set(`${p.sku} deleted`);
      },
      error: this.fail,
    });
  }

  restock() {
    const url = `/api/inventory/stock/${encodeURIComponent(this.sku.trim())}`;
    this.http
      .post(`${url}/restock`, { quantity: this.quantity })
      .pipe(switchMap(() => this.http.get<{ sku: string; available: number }>(url)))
      .subscribe({
        next: (stock) => {
          this.stock.reload();
          this.message.set(`${stock.sku}: ${stock.available} available`);
        },
        error: this.fail,
      });
  }
}
