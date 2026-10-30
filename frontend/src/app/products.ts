import { CurrencyPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface Product {
  sku: string;
  name: string;
  category: string;
  price: number;
  description?: string;
  attributes: Record<string, string>;
}

// The shop's home page
@Component({
  selector: 'app-products',
  imports: [CurrencyPipe, RouterLink],
  template: `
    <h1 class="h3 mb-3">Furniture</h1>
    @if (products.hasValue()) {
      <div class="row row-cols-1 row-cols-sm-2 row-cols-lg-3 g-3">
        @for (product of products.value(); track product.sku) {
          <div class="col">
            <a class="card h-100 text-decoration-none" [routerLink]="['/products', product.sku]">
              <div class="card-body">
                <h2 class="h5 card-title">{{ product.name }}</h2>
                <p class="card-subtitle text-body-secondary">{{ product.category }}</p>
              </div>
              <div class="card-footer fw-semibold">{{ product.price | currency: 'EUR' }}</div>
            </a>
          </div>
        } @empty {
          <p>No products yet.</p>
        }
      </div>
    } @else if (products.error()) {
      <p role="alert">The shop can't be reached right now.</p>
    }
  `,
})
export class Products {
  protected readonly products = httpResource<Product[]>(() => '/api/catalog/products');
}
