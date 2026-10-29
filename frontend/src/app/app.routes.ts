import { Routes } from '@angular/router';
import { hasRole } from './auth';

export const routes: Routes = [
  // Loaded only when an admin opens it, so shoppers don't download admin pages
  { path: 'admin/stock', loadComponent: () => import('./stock-admin').then((m) => m.StockAdmin), canActivate: [hasRole('admin')] },
];
