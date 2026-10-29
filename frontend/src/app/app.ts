import { Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import Keycloak from 'keycloak-js';

@Component({
  imports: [RouterLink, RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  // Signing in and out are full-page redirects to Keycloak, so this never changes while the page is open
  protected readonly keycloak = inject(Keycloak);

  // Back to the home page: the current one may need a role, and its guard would send the user straight to sign in
  protected signOut() {
    return this.keycloak.logout({ redirectUri: location.origin });
  }
}
