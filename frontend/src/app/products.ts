import { CurrencyPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface Product {
  sku: string;
  name: string;
  category: string;
  price: number;
  description?: string;
  attributes: Record<string, string>;
  imageUrl?: string | null;
}

export const categoriesOf = (products: Product[]) => [...new Set(products.map((p) => p.category))].sort();

// Each room with its products, and the first photo the catalog has for it
export const roomsOf = (products: Product[]) =>
  categoriesOf(products).map((name) => {
    const inRoom = products.filter((p) => p.category === name);
    return { name, products: inRoom, imageUrl: inRoom.find((p) => p.imageUrl)?.imageUrl };
  });

export type Room = ReturnType<typeof roomsOf>[number];

// No category or search means all of them. Spaces around the search and upper or lower case don't matter.
export const matching = (products: Product[], category?: string, search = '') =>
  products.filter(
    (p) => (!category || p.category === category) && p.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

// A product's photo, name and price, on the home page and in the shop
@Component({
  selector: 'app-product-card',
  imports: [CurrencyPipe, RouterLink],
  template: `
    <a class="product-card" [routerLink]="['/products', product().sku]">
      <!-- No alt text: the link already says the product's name -->
      @if (product().imageUrl) {
        <img class="product-photo" [src]="product().imageUrl" alt="" loading="lazy" />
      } @else {
        <span class="product-photo" aria-hidden="true">{{ product().name[0] }}</span>
      }
      <span class="product-card-text">
        <span>{{ product().name }}</span>
        <strong>{{ product().price | currency: 'EUR' }}</strong>
      </span>
    </a>
  `,
})
export class ProductCard {
  readonly product = input.required<Product>();
}

// All the furniture, narrowed by room and search
@Component({
  selector: 'app-products',
  imports: [ProductCard, RouterLink],
  template: `
    <nav class="breadcrumbs" aria-label="Breadcrumb"><a routerLink="/">Home</a> / {{ title() }}</nav>
    <h1>{{ title() }}</h1>
    @if (q()) {
      <p>Results for "{{ q() }}". <a routerLink="/products" [queryParams]="{ category: category() ?? null }">Clear search</a></p>
    }
    @if (products.hasValue()) {
      <div class="shop">
        <aside class="shop-filters">
          <h2 class="h5">Room</h2>
          <ul>
            <li>
              <a routerLink="/products" [queryParams]="{ q: q() ?? null }" [class.active]="!category()">
                All furniture <span>{{ count() }}</span>
              </a>
            </li>
            @for (name of categories(); track name) {
              <li>
                <a routerLink="/products" [queryParams]="{ category: name, q: q() ?? null }" [class.active]="name === category()">
                  {{ name }} <span>{{ count(name) }}</span>
                </a>
              </li>
            }
          </ul>
        </aside>
        <div class="product-grid">
          @for (product of matches(); track product.sku) {
            <app-product-card [product]="product" />
          } @empty {
            <p>{{ all().length ? 'Nothing matches that.' : 'No products yet.' }}</p>
          }
        </div>
      </div>
    } @else if (products.error()) {
      <p role="alert">The shop can't be reached right now.</p>
    }
  `,
})
export class Products {
  // From the URL (?category=Bedroom&q=oak), so the header's menu and search can set them and a filtered page can be shared
  readonly category = input<string>();
  readonly q = input<string>();

  // filters in the browser, fine while the catalog comes in one response; query MongoDB once it pages
  protected readonly products = httpResource<Product[]>(() => '/api/catalog/products');
  protected readonly all = computed(() => (this.products.hasValue() ? this.products.value() : []));
  protected readonly categories = computed(() => categoriesOf(this.all()));
  protected readonly matches = computed(() => matching(this.all(), this.category(), this.q()));
  protected readonly title = computed(() => this.category() ?? (this.q() ? 'Search' : 'All furniture'));

  protected count(category?: string) {
    return matching(this.all(), category, this.q()).length;
  }
}
