import { ConfigurationVariant, Id, Network } from '../../core/models';

/**
 * The fork forest of a project - which network was derived from which.
 *
 * Free of Angular and of HTTP, like `core/time-units.ts` and `features/scenario-builder/timeline.ts`.
 * The reason is the same: the part that has to be right is the shape, and a pure function is the
 * only version of it that can be tested exhaustively - including the shapes that should be
 * impossible.
 *
 * ## Why a flat list and not a nested one
 *
 * A nested structure would need a self-referencing component to render, and Angular has no clean
 * way to write one. Depth, sibling position and the ancestor rails are all decided here instead, so
 * the template is a single `@for` over rows that already know how to draw themselves.
 *
 * ## Every network appears exactly once
 *
 * That is the invariant the whole view rests on: the tree sits beside the network table, and a
 * network in one and not the other would read as a bug in whichever the reader trusts less. So a
 * network whose base is missing from the list is a root, and - since `lever_changes_json` is written
 * by a fork that always predates its copy, a cycle cannot occur but also cannot be ruled out by this
 * function's inputs - anything the walk cannot reach is appended as a root rather than dropped.
 */

/** One row of the rendered forest: a network, and how it came to be there. */
export interface ProvenanceEntry {
  readonly network: Network;
  /** The provenance record that derived it (`CONFIGURATION_VARIANT`); null for a root. */
  readonly variant: ConfigurationVariant | null;
  /** The network it was forked from; null for a root. */
  readonly parent: Network | null;
  /** 0 for a root, 1 for a fork of a root, and so on. */
  readonly depth: number;
  /** Last among its siblings - draws the elbow rather than the tee. */
  readonly last: boolean;
  /**
   * One flag per ancestor column, `depth - 1` of them: whether that ancestor has siblings still to
   * come, and so whether its vertical rail continues past this row. Empty for depth 0 and 1.
   *
   * Roots contribute no rail: they are separate trees, and a line running from one baseline down
   * past the next would claim a relationship the data does not have.
   */
  readonly rails: readonly boolean[];
  /** How many networks were forked directly from this one. */
  readonly childCount: number;
}

/**
 * Arranges a project's networks into the forest its variant records describe.
 *
 * `variants` may be incomplete - the sweep that gathers it tolerates a failed request (see
 * `ProvenanceStore`) - in which case the networks whose edge was missed surface as roots. That is
 * why the store reports partiality rather than letting the tree imply it.
 */
export function buildProvenance(
  networks: readonly Network[],
  variants: readonly ConfigurationVariant[],
): readonly ProvenanceEntry[] {
  const byId = new Map<Id, Network>(networks.map((network) => [network.id, network]));

  // Keyed by the *derived* network: a network materialises at most one variant record (a 1:1
  // relationship), so this map is the parent pointer. Self-references and bases outside the project
  // are dropped rather than trusted - either would make the walk below a lie.
  const parentOf = new Map<Id, ConfigurationVariant>();
  for (const variant of variants) {
    const childId = variant.network.id;
    const usable =
      byId.has(childId) && byId.has(variant.baseNetworkId) && childId !== variant.baseNetworkId;
    if (usable) {
      parentOf.set(childId, variant);
    }
  }

  const childrenOf = new Map<Id, Network[]>();
  for (const network of networks) {
    const variant = parentOf.get(network.id);
    if (!variant) {
      continue;
    }
    const siblings = childrenOf.get(variant.baseNetworkId);
    if (siblings) {
      siblings.push(network);
    } else {
      childrenOf.set(variant.baseNetworkId, [network]);
    }
  }
  for (const siblings of childrenOf.values()) {
    siblings.sort(byNameThenVersion);
  }

  const roots = networks.filter((network) => !parentOf.has(network.id)).sort(byBaselineThenName);

  const entries: ProvenanceEntry[] = [];
  const visited = new Set<Id>();

  const walk = (network: Network, depth: number, last: boolean, rails: readonly boolean[]): void => {
    if (visited.has(network.id)) {
      return;
    }
    visited.add(network.id);
    const children = childrenOf.get(network.id) ?? [];
    const variant = parentOf.get(network.id) ?? null;
    entries.push({
      network,
      variant,
      parent: variant ? (byId.get(variant.baseNetworkId) ?? null) : null,
      depth,
      last,
      rails,
      childCount: children.length,
    });
    // A child's rails are its parent's, plus one column saying whether the parent's own line
    // continues. Roots add no column, which is what keeps the trees visually separate.
    const childRails = depth === 0 ? [] : [...rails, !last];
    children.forEach((child, index) =>
      walk(child, depth + 1, index === children.length - 1, childRails),
    );
  };

  roots.forEach((root, index) => walk(root, 0, index === roots.length - 1, []));

  // Unreachable only if the parent pointers form a cycle, which the fork path cannot produce. Shown
  // as roots anyway: a network silently absent from a tree that claims to hold every configuration
  // is the one failure the view must not have.
  for (const network of networks) {
    if (!visited.has(network.id)) {
      visited.add(network.id);
      entries.push({
        network,
        variant: parentOf.get(network.id) ?? null,
        parent: null,
        depth: 0,
        last: true,
        rails: [],
        childCount: (childrenOf.get(network.id) ?? []).length,
      });
    }
  }

  return entries;
}

/** True once some network in the project was forked from another - the tree is worth drawing. */
export function hasForks(entries: readonly ProvenanceEntry[]): boolean {
  return entries.some((entry) => entry.depth > 0);
}

/** Variants of one name sort together, newest version last - the order a reader expects them in. */
function byNameThenVersion(a: Network, b: Network): number {
  return a.name.localeCompare(b.name) || a.version - b.version;
}

/** The baseline heads the forest: it is the configuration the others are measured against. */
function byBaselineThenName(a: Network, b: Network): number {
  if (a.baseline !== b.baseline) {
    return a.baseline ? -1 : 1;
  }
  return byNameThenVersion(a, b);
}
