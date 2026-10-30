import { CurrencyPipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, httpResource } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { switchMap } from 'rxjs';
import { Product } from './products';

// One "seats: 3" per line becomes { seats: '3' }
export const parseAttributes = (text: string): Record<string, string> =>
  Object.fromEntries([...text.matchAll(/^(.+?):(.*)$/gm)].map(([, name, value]) => [name.trim(), value.trim()]));

// Admins edit the catalog (catalog-service) and add units to a SKU (inventory-service)
@Component({
  selector: 'app-admin',
  imports: [CurrencyPipe, FormsModule],
  template: `
    <h1 class="h3 mb-3">Admin</h1>
    <div class="row g-4">
      <section class="col-lg-6">
        <h2 class="h5">Products</h2>
        @if (products.hasValue()) {
          <table class="table table-sm align-middle">
            <tbody>
              @for (p of products.value(); track p.sku) {
                <tr>
                  <td>{{ p.sku }}</td>
                  <td>{{ p.name }}</td>
                  <td class="text-end">{{ p.price | currency: 'EUR' }}</td>
                  <td class="text-end"><button class="btn btn-link btn-sm" type="button" (click)="edit(p)">Edit</button></td>
                </tr>
              } @empty {
                <tr><td>No products yet.</td></tr>
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
  `,
})
export class Admin {
  private readonly http = inject(HttpClient);
  protected readonly products = httpResource<Product[]>(() => '/api/catalog/products');
  product = { sku: '', name: '', category: '', price: null as number | null, description: '' };
  attributes = '';
  sku = '';
  quantity = 1;
  readonly message = signal('');

  edit(p: Product) {
    this.product = { sku: p.sku, name: p.name, category: p.category, price: p.price, description: p.description ?? '' };
    this.attributes = Object.entries(p.attributes).map(([name, value]) => `${name}: ${value}`).join('\n');
    this.sku = p.sku; // restock the product being edited
  }

  save() {
    const { sku, ...product } = this.product;
    this.http
      .put(`/api/catalog/products/${encodeURIComponent(sku.trim())}`, { ...product, attributes: parseAttributes(this.attributes) })
      .subscribe({
        next: () => {
          this.products.reload();
          this.message.set(`${sku} saved`);
        },
        error: (e: HttpErrorResponse) => this.message.set(e.error?.detail ?? e.message),
      });
  }

  restock() {
    const url = `/api/inventory/stock/${encodeURIComponent(this.sku.trim())}`;
    this.http
      .post(`${url}/restock`, { quantity: this.quantity })
      .pipe(switchMap(() => this.http.get<{ sku: string; available: number }>(url)))
      .subscribe({
        next: (stock) => this.message.set(`${stock.sku}: ${stock.available} available`),
        error: (e: HttpErrorResponse) => this.message.set(e.error?.detail ?? e.message),
      });
  }
}
