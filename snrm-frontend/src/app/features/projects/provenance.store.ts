import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { ConfigurationVariant, Id, Network } from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { LoadState } from './projects.store';
import { ProvenanceEntry, buildProvenance, hasForks } from './provenance-tree';

/** How many `variants` requests the sweep keeps in the air at once, after the first. */
const CONCURRENCY = 6;

/**
 * The fork forest of the project on the dashboard - `GET /networks/{id}/variants`.
 *
 * ## What the screen is for
 *
 * The network table answers "what configurations exist"; nothing answered "what did I derive from
 * what". `configuration_variant` records exactly that, and the comparison view already leans on it
 * for the lever annotation of each column - but a column legend is not a lineage, and a researcher
 * with nine variants is asking about the lineage.
 *
 * ## Why the sweep, and why it is usually one request
 *
 * `GET /networks/{id}/variants` returns the networks forked **directly** from one network
 * (`findByBaseNetworkId`), not its transitive descendants, so the forest is assembled from one
 * response per potential parent. Two things keep that honest:
 *
 * - **The first probe goes alone, and it is the baseline.** A project whose variants were all forked
 *   from the baseline - the shape the fork prompt produces - is fully
 *   described by that single response, and the sweep stops there.
 * - **The sweep stops as soon as every edge is known.** A network materialises at most one variant
 *   record (ER 1:1) and at least one network must be a root, so once `networks.length - 1` derived
 *   networks have been seen there is nothing left to find, whatever the unqueried networks would
 *   have said.
 *
 * A chain of forks still costs a request per link. `GET /projects/{id}/variants` on the backend
 * would replace the whole sweep with the one call this view deserves, and is the right fix if a
 * project ever grows past a couple of dozen networks - the same trade `product-usage.service.ts`
 * records for its own sweep.
 *
 * ## A failed request degrades rather than aborts
 *
 * A network whose children could not be read contributes no edges, so its forks surface as roots.
 * That is a wrong-looking tree, not a missing one, which is why {@link partial} exists and the view
 * says so on screen: an unexplained root is indistinguishable from a baseline otherwise.
 */
@Injectable({ providedIn: 'root' })
export class ProvenanceStore {
  private readonly api = inject(ApiService);

  private readonly _entries = signal<readonly ProvenanceEntry[]>([]);
  private readonly _state = signal<LoadState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _partial = signal(false);

  readonly entries = this._entries.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  /** True when some lineage could not be read, so a root on screen may not really be one. */
  readonly partial = this._partial.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');
  /** Whether anything was forked at all. The panel does not render otherwise - see the component. */
  readonly hasForks = computed(() => hasForks(this._entries()));

  /**
   * Which sweep is current, so a slow response for a project the user has navigated away from
   * cannot write itself over a newer one. Bumped by every {@link load} and by {@link reset}.
   */
  private generation = 0;

  /** Discards the forest and abandons a sweep in flight. Called when the dashboard changes project. */
  reset(): void {
    this.generation += 1;
    this._entries.set([]);
    this._state.set('idle');
    this._error.set(null);
    this._partial.set(false);
  }

  /**
   * Reads the provenance of a project's networks. Never rejects; failures land in
   * {@link error} and {@link partial}.
   *
   * Takes the network list rather than a project id because the dashboard has already loaded it and
   * a second `GET /projects/{id}/networks` could disagree with the table beside the tree.
   */
  async load(networks: readonly Network[]): Promise<void> {
    const generation = ++this.generation;
    this._state.set('loading');
    this._error.set(null);
    this._partial.set(false);

    // Fewer than two networks cannot hold a fork, so the cheapest sweep is the one not made.
    if (networks.length < 2) {
      this.publish(generation, buildProvenance(networks, []), false);
      return;
    }

    const order = [...networks].sort(likeliestParentFirst);
    const found = new Map<Id, ConfigurationVariant>();
    const known = new Set<Id>(networks.map((network) => network.id));
    let failure: unknown = null;
    let failures = 0;
    let requests = 0;

    for (let start = 0; start < order.length; ) {
      // The first goes alone: for a flat forest under one baseline its answer is the whole tree,
      // and firing five more beside it would spend the requests this stop-early exists to save.
      const batch = order.slice(start, start + (start === 0 ? 1 : CONCURRENCY));
      const results = await Promise.all(batch.map((network) => this.variantsOf(network.id)));
      if (generation !== this.generation) {
        return;
      }
      start += batch.length;
      requests += batch.length;
      for (const result of results) {
        if ('failure' in result) {
          failures += 1;
          failure ??= result.failure;
          continue;
        }
        for (const variant of result.variants) {
          if (known.has(variant.network.id)) {
            found.set(variant.network.id, variant);
          }
        }
      }
      // At least one network is a root, so `n - 1` edges is every edge there can be.
      if (found.size >= networks.length - 1) {
        break;
      }
    }

    if (failures === requests) {
      this.fail(generation, failure);
      return;
    }
    this.publish(generation, buildProvenance(networks, [...found.values()]), failures > 0);
  }

  /** One `GET /networks/{id}/variants`, with the failure carried rather than thrown. */
  private async variantsOf(
    networkId: Id,
  ): Promise<{ variants: readonly ConfigurationVariant[] } | { failure: unknown }> {
    try {
      return {
        variants: await firstValueFrom(
          this.api.get<ConfigurationVariant[]>(`/networks/${networkId}/variants`),
        ),
      };
    } catch (failure: unknown) {
      return { failure };
    }
  }

  private fail(generation: number, failure: unknown): void {
    if (generation !== this.generation) {
      return;
    }
    this._entries.set([]);
    this._error.set(problemMessage(failure, 'Could not read how these networks were derived.'));
    this._partial.set(false);
    this._state.set('error');
  }

  private publish(
    generation: number,
    entries: readonly ProvenanceEntry[],
    partial: boolean,
  ): void {
    if (generation !== this.generation) {
      return;
    }
    this._entries.set(entries);
    this._error.set(null);
    this._partial.set(partial);
    this._state.set('ready');
  }
}

/**
 * The order the sweep asks in: baseline first, then oldest version, then name.
 *
 * A heuristic in service of the stop-early rule rather than of correctness - the answer is the same
 * whatever the order. The baseline is where the fork prompt starts from, and a low version number is
 * what a network forked from looks like, so this is the order that most often makes the second
 * request unnecessary.
 */
function likeliestParentFirst(a: Network, b: Network): number {
  if (a.baseline !== b.baseline) {
    return a.baseline ? -1 : 1;
  }
  return a.version - b.version || a.name.localeCompare(b.name);
}
