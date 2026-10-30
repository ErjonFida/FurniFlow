import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, httpResource } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

interface Order {
  id: string;
  sku: string;
  quantity: number;
  status: 'RESERVED' | 'PAID';
  reservedUntil: string;
}

// The signed-in customer's orders. order-service only ever returns their own.
@Component({
  selector: 'app-orders',
  imports: [DatePipe, RouterLink],
  template: `
    <h1 class="h3 mb-3">My orders</h1>
    @if (orders.hasValue()) {
      <table class="table align-middle">
        <thead>
          <tr><th>Product</th><th>Quantity</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          @for (order of orders.value(); track order.id) {
            <tr>
              <td><a [routerLink]="['/products', order.sku]">{{ order.sku }}</a></td>
              <td>{{ order.quantity }}</td>
              @if (order.status === 'PAID') {
                <td>Paid</td>
                <td></td>
              } @else if (isHeld(order)) {
                <td>Reserved until {{ order.reservedUntil | date: 'shortTime' }}</td>
                <td><button class="btn btn-primary btn-sm" type="button" (click)="pay(order)">Pay</button></td>
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

  protected isHeld(order: Order) {
    return new Date(order.reservedUntil) > new Date();
  }

  pay(order: Order) {
    this.http.post(`/api/orders/${order.id}/pay`, null).subscribe({
      next: () => this.orders.reload(),
      error: (e: HttpErrorResponse) => this.message.set(e.error?.detail ?? e.message),
    });
  }
}
