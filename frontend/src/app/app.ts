import { CurrencyPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import Keycloak from 'keycloak-js';
import { filter } from 'rxjs';
import { Cart } from './cart';
import { Product, Room, roomsOf, srcsetOf } from './products';

@Component({
  imports: [CurrencyPipe, RouterLink, RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  // Not a signal: signing in or out reloads the page, so this only changes mid-page if Keycloak answers after the
  // shop stopped waiting (see checkSignIn). The header catches up when it next redraws.
  protected readonly keycloak = inject(Keycloak);
  protected readonly cart = inject(Cart);

  // loads the whole catalog for the menu, and a product an admin just added shows after a reload; ask
  // catalog-service for just the menu's fields once it pages
  private readonly products = httpResource<Product[]>(() => '/api/catalog/products');
  protected readonly rooms = computed(() => roomsOf(this.products.hasValue() ? this.products.value() : []));
  protected readonly activeRoom = signal<Room | null>(null); // the one the menu's right side shows
  protected readonly srcsetOf = srcsetOf;
  private menuCloseTimer?: ReturnType<typeof setTimeout>;

  // The menu stays open while the mouse is on the button or on the menu. Touch has no hover, so it's left to taps.
  protected keepMenuOpen(event: PointerEvent, menu: HTMLElement) {
    if (event.pointerType === 'mouse') {
      clearTimeout(this.menuCloseTimer);
      if (!menu.matches(':popover-open')) {
        menu.showPopover();
      }
    }
  }

  // Not at once: the mouse needs a moment to cross the gap between the button and the menu
  protected closeMenuSoon(event: PointerEvent, menu: HTMLElement) {
    if (event.pointerType === 'mouse') {
      this.menuCloseTimer = setTimeout(() => {
        if (menu.matches(':popover-open')) {
          menu.hidePopover();
        }
      }, 200);
    }
  }

  // The search box says what the URL says, so it's right after a reload and empties when the search is cleared
  private readonly navigated = toSignal(this.router.events.pipe(filter((event) => event instanceof NavigationEnd)));
  protected readonly searched = computed(() => {
    this.navigated();
    return this.router.parseUrl(this.router.url).queryParams['q'] ?? '';
  });

  protected search(text: string) {
    return this.router.navigate(['/products'], { queryParams: { q: text.trim() || null } });
  }

  // Back to the home page: the current one may need a role, and its guard would send the user straight to sign in.
  // The cart is kept in the browser, so it goes too, or the next person on this computer would find it.
  protected signOut() {
    this.cart.clear();
    return this.keycloak.logout({ redirectUri: location.origin });
  }
}
