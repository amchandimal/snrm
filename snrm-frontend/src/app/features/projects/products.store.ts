import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, tap, throwError } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { Id, Product, ProductRequest } from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { LoadState } from './projects.store';

/**
 * The product catalogue of one project (FR-01).
 *
 * ## Why this is on the project and not in the editor
 *
 * A product is scoped to the **project**, not to a network (`PRODUCT`): a configuration
 * variant is a different structure over the *same* catalogue, which is what makes two variants' cost
 * and service metrics comparable at all. Putting the catalogue inside the editor would attach it to
 * one network and invite a per-network catalogue, which the comparison view could not read across.
 *
 * ## Why the catalogue is load-bearing rather than a detail
 *
 * Nothing in the tool can carry demand until it has a product. A node's demand lives on a
 * `node_product` row, so an empty catalogue means no node can be a demand sink, and
 * `POST /simulations` refuses a network with no demand at all (`NETWORK_HAS_NO_DEMAND`).
 * A project whose catalogue is empty is a project that cannot be simulated, which is why the
 * dashboard says so rather than leaving it to be discovered at the launcher.
 *
 * All four endpoints are the ones the API already serves - `GET`/`POST /projects/{id}/products`
 * and `PUT`/`DELETE /products/{id}`. See `network/ProductController` on the backend for why the
 * writes hang off the project rather than off a network.
 */
@Injectable({ providedIn: 'root' })
export class ProductsStore {
  private readonly api = inject(ApiService);

  private readonly _projectId = signal<Id | null>(null);
  private readonly _products = signal<readonly Product[]>([]);
  private readonly _state = signal<LoadState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _creating = signal(false);
  private readonly _busyFor = signal<Id | null>(null);

  readonly projectId = this._projectId.asReadonly();
  readonly products = this._products.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  readonly creating = this._creating.asReadonly();
  /** Which product has a write in flight, so one row shows progress rather than the whole table. */
  readonly busyFor = this._busyFor.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');

  /**
   * True once the catalogue is known to be empty - `'ready'` and nothing in it.
   *
   * Distinct from "not loaded yet" on purpose: the dashboard turns this into a warning that the
   * project cannot yet be simulated, and showing that warning while the request is still in flight
   * would accuse a perfectly stocked project of being empty for as long as the round trip takes.
   */
  readonly isEmpty = computed(() => this._state() === 'ready' && this._products().length === 0);

  dismissError(): void {
    this._error.set(null);
  }

  /** `GET /projects/{id}/products`. Re-reads unless this project is already loaded and `force` is false. */
  load(projectId: Id, force = true): void {
    if (!force && this._state() === 'ready' && this._projectId() === projectId) {
      return;
    }
    this._projectId.set(projectId);
    this._state.set('loading');
    this._products.set([]);
    this.api.get<Product[]>(`/projects/${projectId}/products`).subscribe({
      next: (products) => {
        this._products.set(sortByName(products));
        this._error.set(null);
        this._state.set('ready');
      },
      error: (failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not load the products of this project.'));
        this._state.set('error');
      },
    });
  }

  /**
   * `POST /projects/{id}/products` - 409 `DUPLICATE_NAME` when the project already has that name.
   *
   * Not blocked by a frozen network, and it should not be: creating a product changes no network's
   * structure, so the immutability rule has nothing to protect here.
   */
  create(projectId: Id, request: ProductRequest): Observable<Product> {
    this._creating.set(true);
    return this.api.post<Product>(`/projects/${projectId}/products`, normalise(request)).pipe(
      tap((product) => this.upsert(product)),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not create the product.'));
        return throwError(() => failure);
      }),
      finalize(() => this._creating.set(false)),
    );
  }

  /**
   * `PUT /products/{id}` - a **full replacement** of the client-supplied fields.
   *
   * Both fields travel, always. `ProductRequest.unitValue` is a primitive on the backend, so an
   * omitted one arrives as 0 rather than as "leave alone": a rename that sent only the name would
   * quietly zero the unit value, and with it every monetary metric that weights unserved demand by
   * it. That is why the row edits the two together instead of offering a bare rename.
   */
  replace(productId: Id, request: ProductRequest): Observable<Product> {
    this._busyFor.set(productId);
    return this.api.put<Product>(`/products/${productId}`, normalise(request)).pipe(
      tap((product) => this.upsert(product)),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not update the product.'));
        return throwError(() => failure);
      }),
      finalize(() => this._busyFor.set(null)),
    );
  }

  /**
   * `DELETE /products/{id}` - 204, and the `node_product` rows referencing it go with it.
   *
   * **This cascades; it does not refuse.** `fk_node_product_product` is `ON DELETE CASCADE`, so
   * every node in every network of the project loses its demand, inventory, safety-stock and
   * holding-cost figures for this product - including on networks a simulation run has frozen, which
   * `ProductService.delete` records as the one place the immutability rule can be reached
   * around. The caller is therefore expected to have shown what will be lost *before* asking; see
   * `ProductUsageService`.
   *
   * The row is dropped only once the server has confirmed, in line with every other delete here.
   */
  delete(productId: Id): Observable<void> {
    this._busyFor.set(productId);
    return this.api.delete<void>(`/products/${productId}`).pipe(
      tap(() => {
        this._products.update((list) => list.filter((product) => product.id !== productId));
        this._error.set(null);
        this._state.set('ready');
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not delete the product.'));
        return throwError(() => failure);
      }),
      finalize(() => this._busyFor.set(null)),
    );
  }

  private upsert(product: Product): void {
    this._products.update((list) => {
      const without = list.filter((existing) => existing.id !== product.id);
      return sortByName([...without, product]);
    });
    this._error.set(null);
    this._state.set('ready');
  }
}

/** Trimmed name and an explicit unit value, so neither depends on what the server defaults to. */
function normalise(request: ProductRequest): ProductRequest {
  return { name: request.name.trim(), unitValue: request.unitValue ?? 0 };
}

/** The backend lists products ordered by name; keep local mutations in the same order. */
function sortByName(products: readonly Product[]): readonly Product[] {
  return [...products].sort((a, b) => a.name.localeCompare(b.name));
}
