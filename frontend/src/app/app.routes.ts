import { Routes } from '@angular/router';
import { hasRole } from './auth';
import { Products } from './products';

export const routes: Routes = [
  { path: '', component: Products },
  { path: 'products/:sku', loadComponent: () => import('./product-page').then((m) => m.ProductPage) },
  { path: 'orders', loadComponent: () => import('./orders').then((m) => m.Orders), canActivate: [hasRole('customer')] },
  // Loaded only when an admin opens it, so shoppers don't download admin pages
  { path: 'admin', loadComponent: () => import('./admin').then((m) => m.Admin), canActivate: [hasRole('admin')] },
];
