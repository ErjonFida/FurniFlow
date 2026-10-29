import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { switchMap } from 'rxjs';

// Admins add units to a SKU. Becomes the admin dashboard in step 4.
@Component({
  selector: 'app-stock-admin',
  imports: [FormsModule],
  template: `
    <h1 class="h3 mb-3">Stock</h1>
    <form class="row g-2 align-items-end" (ngSubmit)="restock()">
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
    @if (message()) {
      <p class="mt-3" role="status">{{ message() }}</p>
    }
  `,
})
export class StockAdmin {
  private readonly http = inject(HttpClient);
  sku = '';
  quantity = 1;
  readonly message = signal('');

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
