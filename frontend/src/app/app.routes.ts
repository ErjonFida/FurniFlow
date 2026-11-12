import { Routes } from '@angular/router';
import { hasRole } from './auth';
import { Home } from './home';
import { Products } from './products';

export const routes: Routes = [
  { path: '', component: Home },
  { path: 'products', component: Products },
  { path: 'products/:sku', loadComponent: () => import('./product-page').then((m) => m.ProductPage) },
  { path: 'cart', loadComponent: () => import('./cart').then((m) => m.CartPage) },
  { path: 'orders', loadComponent: () => import('./orders').then((m) => m.Orders), canActivate: [hasRole('customer')] },
  { path: 'admin', loadComponent: () => import('./admin').then((m) => m.Admin), canActivate: [hasRole('admin')] },
];
