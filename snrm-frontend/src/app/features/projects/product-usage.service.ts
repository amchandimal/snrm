import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { Id, Network, NetworkNode, NodeProduct } from '../../core/models';

/** Every node of one network that carries the product being looked up. */
export interface NetworkUsage {
  readonly networkId: Id;
  /** `name` and `version` together - variants share a name, so the name alone names several. */
  readonly label: string;
  /** False once a simulation run has frozen the network. */
  readonly editable: boolean;
  readonly nodeNames: readonly string[];
}

/** Where a product is carried across a whole project. */
export interface ProductUsage {
  readonly productId: Id;
  /** Only the networks that carry it; a network with no such node is not listed. */
  readonly networks: readonly NetworkUsage[];
  readonly nodeCount: number;
  /** Of {@link networks}, how many a run has frozen - those lose rows the editor could not have. */
  readonly frozenCount: number;
  /**
   * True when some request in the sweep failed, so the figures are a floor rather than a total.
   *
   * Reported rather than thrown: a partial answer still names nodes that will certainly lose their
   * rows, and refusing to show it would leave the dialog with less to say than it actually knows.
   */
  readonly partial: boolean;
}

/** How many requests the sweep keeps in the air at once. */
const CONCURRENCY = 6;

/**
 * Which nodes carry a product, across every network of a project.
 *
 * ## Why this exists at all
 *
 * `DELETE /products/{id}` **cascades**: `fk_node_product_product` is `ON DELETE CASCADE`, so the
 * demand, inventory, safety-stock and holding-cost figures for that product disappear from every
 * node of every network in the project - frozen networks included, which `ProductService.delete`
 * records as the one place the immutability rule can be reached around. There is no
 * conflict to catch and nothing to undo. So the only honest place to say what will be lost is
 * *before* the request, which is what this sweep is for.
 *
 * ## Why it is a sweep and not an endpoint
 *
 * The API has no "which nodes carry product X". Answering it costs one request for the project's
 * networks, one per network for its nodes, and one per node for its rows. That is dear - a 50-node
 * project with two variants is about a hundred requests - so it runs **only when the delete dialog
 * opens**, never on the list, and it is capped at {@link CONCURRENCY} in flight.
 *
 * A failure inside the sweep degrades rather than aborts, following `countNodeProducts` in the
 * editor store: the dialog raises its guard when the answer is incomplete instead of pretending the
 * product is unused. A cheap `GET /products/{id}/usage` on the backend would replace all of this,
 * and is the right fix if the catalogue ever grows past a handful of products.
 */
@Injectable({ providedIn: 'root' })
export class ProductUsageService {
  private readonly api = inject(ApiService);

  private readonly _usage = signal<ProductUsage | null>(null);
  private readonly _scanning = signal(false);

  /** The last completed sweep, or null while one is running or before any has been asked for. */
  readonly usage = this._usage.asReadonly();
  readonly scanning = this._scanning.asReadonly();

  /**
   * True only when the sweep finished, finished completely, and found nothing.
   *
   * The one condition under which deleting a product destroys no figures. Everything else - still
   * running, partial, or in use - is treated as "in use" by the dialog, because not knowing is not
   * the same as knowing there is nothing there.
   */
  readonly knownUnused = computed(() => {
    const usage = this._usage();
    return usage !== null && !usage.partial && usage.nodeCount === 0;
  });

  /**
   * Which generation of the sweep is current.
   *
   * Bumped by every {@link scan} and by {@link reset}, so a sweep whose dialog has since been
   * cancelled - or which has been superseded by a sweep for a different product - cannot write its
   * answer over a newer one when its last request finally returns.
   */
  private generation = 0;

  /** Discards any answer and abandons a sweep in flight. Called when the dialog closes. */
  reset(): void {
    this.generation += 1;
    this._usage.set(null);
    this._scanning.set(false);
  }

  /** Sweeps the project for nodes carrying `productId`. Never rejects; failures come back `partial`. */
  async scan(projectId: Id, productId: Id): Promise<void> {
    const generation = ++this.generation;
    this._usage.set(null);
    this._scanning.set(true);

    let partial = false;
    const networks = await this.get<Network[]>(`/projects/${projectId}/networks`);
    if (networks === null) {
      // Without the network list there is nothing to sweep, and the caller must not read the empty
      // result as "unused" - which is exactly what `partial` prevents.
      this.publish(generation, { productId, networks: [], nodeCount: 0, frozenCount: 0, partial: true });
      return;
    }

    const found: NetworkUsage[] = [];
    for (const network of networks) {
      if (generation !== this.generation) {
        return;
      }
      const nodes = await this.get<NetworkNode[]>(`/networks/${network.id}/nodes`);
      if (nodes === null) {
        partial = true;
        continue;
      }
      const carrying = await this.nodesCarrying(nodes, productId);
      if (generation !== this.generation) {
        return;
      }
      partial = partial || carrying.partial;
      if (carrying.names.length) {
        found.push({
          networkId: network.id,
          label: `${network.name} v${network.version}`,
          editable: network.editable,
          nodeNames: carrying.names,
        });
      }
    }

    this.publish(generation, {
      productId,
      networks: found,
      nodeCount: found.reduce((total, entry) => total + entry.nodeNames.length, 0),
      frozenCount: found.filter((entry) => !entry.editable).length,
      partial,
    });
  }

  /** Names of the nodes carrying the product, in the order the network lists them. */
  private async nodesCarrying(
    nodes: readonly NetworkNode[],
    productId: Id,
  ): Promise<{ names: readonly string[]; partial: boolean }> {
    const names: string[] = [];
    let partial = false;

    for (let start = 0; start < nodes.length; start += CONCURRENCY) {
      const batch = nodes.slice(start, start + CONCURRENCY);
      const results = await Promise.all(
        batch.map((node) => this.get<NodeProduct[]>(`/nodes/${node.id}/products`)),
      );
      results.forEach((rows, index) => {
        if (rows === null) {
          partial = true;
        } else if (rows.some((row) => row.productId === productId)) {
          names.push(batch[index].name);
        }
      });
    }

    return { names, partial };
  }

  /** One GET, with failure as `null` rather than as a rejection - the sweep tolerates holes. */
  private async get<T>(path: string): Promise<T | null> {
    try {
      return await firstValueFrom(this.api.get<T>(path));
    } catch {
      return null;
    }
  }

  private publish(generation: number, usage: ProductUsage): void {
    if (generation !== this.generation) {
      return;
    }
    this._usage.set(usage);
    this._scanning.set(false);
  }
}
