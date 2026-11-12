import { Component, computed } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catalogResource, ProductCard, roomsOf, srcsetOf } from './products';

@Component({
  selector: 'app-home',
  imports: [ProductCard, RouterLink],
  template: `
    <section class="home-banner">
      <img src="https://images.unsplash.com/photo-1666585958641-4f70887372a1?w=2000&q=75&auto=format" alt="" />
      <div>
        <p class="eyebrow">Furniture for every room</p>
        <h1>Make yourself at home</h1>
        <p>Sofas, beds, desks and garden furniture, with live stock on every product.</p>
        <a class="btn btn-light" routerLink="/products">Browse all furniture</a>
      </div>
    </section>

    @if (products.hasValue()) {
      <section class="home-section">
        <p class="eyebrow">From the catalog</p>
        <h2>Pieces we <b>like</b></h2>
        <!-- The browser scrolls this sideways, by mouse, touch and keyboard -->
        <div class="product-row">
          @for (product of products.value().slice(0, 12); track product.sku) {
            <app-product-card [product]="product" />
          }
        </div>
      </section>

      <section class="home-section">
        <p class="eyebrow">Our collections</p>
        <h2>Shop by <b>room</b></h2>
        <div class="room-grid">
          @for (room of rooms(); track room.name) {
            <a class="room-card" routerLink="/products" [queryParams]="{ category: room.name }">
              @if (room.imageUrl) {
                <img [src]="room.imageUrl" [attr.srcset]="srcsetOf(room.imageUrl)" sizes="auto, 50vw" alt="" loading="lazy" />
              }
              <span class="room-number">{{ ($index + 1).toString().padStart(2, '0') }}</span>
              <span class="room-name">{{ room.name }}</span>
              <span>View the room →</span>
            </a>
          }
        </div>
      </section>
    } @else if (products.error()) {
      <p class="mt-4" role="alert">The shop can't be reached right now.</p>
    }
  `,
})
export class Home {
  protected readonly products = catalogResource();
  protected readonly rooms = computed(() => roomsOf(this.products.hasValue() ? this.products.value() : []));
  protected readonly srcsetOf = srcsetOf;
}
