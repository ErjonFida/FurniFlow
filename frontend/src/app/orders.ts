import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, httpResource } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface Order {
  id: string;
  customer: string;
  // Names and prices as they were when the order was placed. Orders from before those were kept have none (null).
  items: { sku: string; name: string | null; quantity: number; unitPrice: number | null }[];
  total: number | null;
  status: 'RESERVED' | 'PAID' | 'CANCELLED';
  reservedUntil: string;
}

// Unpaid and not yet expired: its units are still set aside
export const isHeld = (order: Order) => new Date(order.reservedUntil) > new Date();

// The signed-in customer's orders. order-service only ever returns their own.
@Component({
  selector: 'app-orders',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  template: `
    <h1>My orders</h1>
    @if (orders.hasValue()) {
      <table class="table align-middle">
        <thead>
          <tr><th>Items</th><th class="text-end">Total</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          @for (order of orders.value(); track order.id) {
            <tr>
              <td>
                @for (item of order.items; track $index) {
                  <div>{{ item.quantity }} × <a [routerLink]="['/products', item.sku]">{{ item.name ?? item.sku }}</a></div>
                }
              </td>
              <td class="text-end">{{ order.total | currency: 'EUR' }}</td>
              @if (order.status === 'PAID') {
                <td>Paid</td>
                <td></td>
              } @else if (order.status === 'CANCELLED') {
                <td>Cancelled</td>
                <td></td>
              } @else if (isHeld(order)) {
                <td>Reserved until {{ order.reservedUntil | date: 'shortTime' }}</td>
                <td class="text-nowrap">
                  <button class="btn btn-primary btn-sm" type="button" (click)="send(order, 'pay')">Pay</button>
                  <button class="btn btn-link btn-sm" type="button" (click)="send(order, 'cancel')">Cancel</button>
                </td>
              } @else {
                <td>Hold expired</td>
                <td></td>
              }
            </tr>
          } @empty {
            <tr><td colspan="4">No orders yet.</td></tr>
          }
        </tbody>
      </table>
    }
    @if (message()) {
      <p class="text-danger" role="alert">{{ message() }}</p>
    }
  `,
})
export class Orders {
  private readonly http = inject(HttpClient);
  protected readonly orders = httpResource<Order[]>(() => '/api/orders');
  protected readonly message = signal('');
  protected readonly isHeld = isHeld;

  send(order: Order, action: 'pay' | 'cancel') {
    this.http.post(`/api/orders/${order.id}/${action}`, null).subscribe({
      next: () => this.orders.reload(),
      error: (e: HttpErrorResponse) => this.message.set(e.error?.detail ?? e.message),
    });
  }
}
