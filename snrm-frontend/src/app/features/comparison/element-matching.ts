import { Id, NetworkLink, NetworkNode } from '../../core/models';

/**
 * The by-name element selection the side-by-side panes share (FR-25).
 *
 * > **The panes share one selection, matched by name.** Clicking a node highlights the node of that
 * > name in every other pane and says plainly where there is none, which is the whole point: two
 * > variants of a configuration share most of their names, so an element present in one and missing
 * > in another is exactly the structural difference a reader opened this view to find … `uq_node` is
 * > what makes the match well defined.
 *
 * Free of Angular, of HTTP and of the DOM, like `pane-grid.ts` beside it. What is pinned here is the
 * matching rule, which is the part that has to be right: every claim this view makes - *this is the
 * same node*, *this one is not in Baseline v3* - is this function's answer rendered.
 * `element-matching.spec.ts` walks every rule below.
 *
 * ## Why a name and not an id
 *
 * Because ids do not survive a fork. `POST /networks/{id}/clone` materialises a new `NETWORK` row
 * with new `NODE` rows, so `DC-1` in the baseline and `DC-1` in the variant are two ids
 * for one thing a researcher would say is the same distribution centre. The id is the identity of a
 * *row*; the name is the identity of the *element*, and it is the one the reader holds in their
 * head. This is the same reasoning that keeps per-element metric rows out of the comparison
 * matrix - "element ids differ across variants" - arrived at from the other side.
 *
 * **`uq_node (network_id, name)` is what makes it well defined**: a name resolves to at
 * most one node within a network, so a match is a node and never a set. Nothing here has to break a
 * tie, and if the constraint were not there this whole view would be guesswork.
 *
 * **A link's identity is its two endpoint names**, which is the identity the archive already uses:
 * its XML writes `<link source="PLANT-1" target="DC-1">` and the restore resolves an event's
 * target the same way. A link has no name column of its own - which is why per-link metric
 * rows are deferred - so the composed pair is not a convention invented here, it is the
 * one the rest of the system already reads.
 *
 * **Direction is part of it.** The model's arcs are directed and the whole reading of a network
 * depends on which way material moves, so `PLANT-1 → DC-1` and `DC-1 → PLANT-1` are two different
 * links. A variant that reversed an arc has made a structural change, and a matcher that shrugged at
 * direction would hide exactly the change this view exists to show. It is reported instead, as
 * {@link ElementMatch.reversed}.
 *
 * ## Matching is exact, and the near miss is reported rather than folded in
 *
 * `DC-1` does not match `dc-1`. The archive resolves a reference by the stored string and so does
 * the import that named the endpoints, so a looser rule here would let this one view
 * claim two elements are the same in a way nothing else in the tool would - and a claim that only
 * one screen believes is worse than an absence.
 *
 * The cost of exactness is a false absence when two variants were imported from files that
 * capitalised differently, so that case is *named* rather than passed over: {@link ElementMatch}
 * carries the near miss, and the pane says "not in Baseline v3 - though it has `dc-1`, which differs
 * only in case". The reader is told the truth about the names and can see the element for
 * themselves.
 */

/** What the panes are selected on: a node's name, or a link's two endpoint names. */
export type ElementKey =
  | { readonly kind: 'node'; readonly name: string }
  | { readonly kind: 'link'; readonly source: string; readonly target: string };

/** One pane's answer to the shared selection. */
export interface ElementMatch<T> {
  /** The element of that name in this pane, or null where the pane has none. */
  readonly element: T | null;
  /**
   * An element whose name differs from the key only in case, or null.
   *
   * Never used as the match - see the module note. It exists so a false absence can be explained
   * instead of leaving the reader to wonder why the dot they can see is not lit.
   */
  readonly nearMiss: T | null;
  /**
   * For a link only: the arc between the same two nodes the other way round.
   *
   * A different link (arcs are directed) and a real structural finding, so it is reported
   * beside the absence rather than counted as a match.
   */
  readonly reversed: T | null;
}

/** Nothing of that name here, and nothing like it. Shared so the identity is stable. */
const NO_MATCH: ElementMatch<never> = { element: null, nearMiss: null, reversed: null };

/** A node as this module needs it: a name is the whole of a node's identity here. */
type NamedNode = Pick<NetworkNode, 'name'>;

/** A link as this module needs it - its two ends, which is what its identity is composed from. */
type LinkEnds = Pick<NetworkLink, 'sourceNodeId' | 'targetNodeId'>;

export function nodeKey(name: string): ElementKey {
  return { kind: 'node', name };
}

export function linkKey(source: string, target: string): ElementKey {
  return { kind: 'link', source, target };
}

/** `DC-1` / `PLANT-1 → DC-1` - how the selection is named on screen, in one place. */
export function keyLabel(key: ElementKey): string {
  return key.kind === 'node' ? key.name : `${key.source} → ${key.target}`;
}

/** `node` / `link` as the word a sentence needs. */
export function keyNoun(key: ElementKey): string {
  return key.kind === 'node' ? 'node' : 'link';
}

/** Whether two selections are the same one - clicking the selected element again clears it. */
export function sameKey(a: ElementKey | null, b: ElementKey | null): boolean {
  if (a === null || b === null) {
    return a === b;
  }
  if (a.kind === 'node' && b.kind === 'node') {
    return a.name === b.name;
  }
  if (a.kind === 'link' && b.kind === 'link') {
    return a.source === b.source && a.target === b.target;
  }
  return false;
}

/** Every node's name by id - what a link's endpoints are resolved through. */
export function nodeNames(
  nodes: readonly Pick<NetworkNode, 'id' | 'name'>[],
): ReadonlyMap<Id, string> {
  return new Map(nodes.map((node) => [node.id, node.name]));
}

/**
 * The key for a node the reader clicked.
 *
 * Total: a node always has a name (`NODE.name` is `NOT NULL`), so there is no failing case
 * to represent.
 */
export function keyOfNode(node: NamedNode): ElementKey {
  return nodeKey(node.name);
}

/**
 * The key for a link the reader clicked, or **null** where either endpoint is not in `names`.
 *
 * Null rather than a key naming `#12`. A link whose endpoints this pane cannot name has no identity
 * the other panes could match on, and a synthesised one would match nothing anywhere while looking
 * exactly like a real selection that happened to be absent everywhere. It cannot happen through the
 * API - a link's endpoints are nodes of its own network - but the two lists arrive in two responses
 * (`RunResultsStore.loadStructure` records the same hazard for the same reason), and a half-loaded
 * pair must not produce a selection that quietly means nothing.
 */
export function keyOfLink(link: LinkEnds, names: ReadonlyMap<Id, string>): ElementKey | null {
  const source = names.get(link.sourceNodeId);
  const target = names.get(link.targetNodeId);
  return source === undefined || target === undefined ? null : linkKey(source, target);
}

/**
 * The node of that name in this pane, with the near miss where there is none.
 *
 * Exact first and always: `uq_node` guarantees at most one, so the loop can stop at it. The
 * case-insensitive pass is only ever consulted when the exact one failed, and it takes the *first*
 * such node in the pane's own order - which is deterministic, since the caller draws that order and
 * a second same-cased-differently node in one network is a thing `uq_node` refuses under the
 * server's collation anyway.
 */
export function matchNode<T extends NamedNode>(
  key: ElementKey,
  nodes: readonly T[],
): ElementMatch<T> {
  if (key.kind !== 'node') {
    return NO_MATCH;
  }
  let nearMiss: T | null = null;
  for (const node of nodes) {
    if (node.name === key.name) {
      return { element: node, nearMiss: null, reversed: null };
    }
    if (nearMiss === null && equalIgnoringCase(node.name, key.name)) {
      nearMiss = node;
    }
  }
  return { element: null, nearMiss, reversed: null };
}

/**
 * The link between those two names in this pane, with the near miss and the reverse arc.
 *
 * `names` is this pane's own {@link nodeNames}; a link whose endpoints it cannot resolve is skipped
 * rather than compared against `undefined`, for {@link keyOfLink}'s reason.
 */
export function matchLink<T extends LinkEnds>(
  key: ElementKey,
  links: readonly T[],
  names: ReadonlyMap<Id, string>,
): ElementMatch<T> {
  if (key.kind !== 'link') {
    return NO_MATCH;
  }
  let nearMiss: T | null = null;
  let reversed: T | null = null;
  let exact: T | null = null;

  for (const link of links) {
    const source = names.get(link.sourceNodeId);
    const target = names.get(link.targetNodeId);
    if (source === undefined || target === undefined) {
      continue;
    }
    if (source === key.source && target === key.target) {
      exact = link;
      break;
    }
    if (source === key.target && target === key.source) {
      reversed ??= link;
    } else if (
      nearMiss === null &&
      equalIgnoringCase(source, key.source) &&
      equalIgnoringCase(target, key.target)
    ) {
      nearMiss = link;
    }
  }

  // A found link owns the answer whole: a reverse arc noticed on the way past is an ordinary second
  // arc between two nodes, not a finding, once the one asked for is there.
  return exact
    ? { element: exact, nearMiss: null, reversed: null }
    : { element: null, nearMiss, reversed };
}

/**
 * What a pane says when the shared selection is not in it - FR-25's "states plainly where there is
 * none".
 *
 * The sentence names the **network**, not the pane, because "not in the third pane" is a statement
 * about a screen and "not in Baseline v3" is a statement about a configuration, which is the finding
 * the reader came for. `networkLabel` is `Name vN`, the same identity the pane's own header shows.
 *
 * The two qualifications are additive and both can apply: a variant may hold `dc-1` *and* the
 * reverse arc. Each is a different reason the absence might not be the one the reader assumed, and
 * dropping either would leave them looking at a dot the tool has just told them does not exist.
 */
export function absenceSentence<T>(
  key: ElementKey,
  networkLabel: string,
  match: ElementMatch<T>,
  nameOf: (element: T) => string,
): string {
  const parts = [`${keyLabel(key)} is not in ${networkLabel}.`];
  if (match.nearMiss) {
    parts.push(
      `It has ${nameOf(match.nearMiss)}, which differs only in case - names are matched exactly, ` +
        'the way the archive resolves them.',
    );
  }
  if (match.reversed) {
    parts.push(
      `It has ${nameOf(match.reversed)}, the same two nodes the other way round - a different arc, ` +
        'since links are directed.',
    );
  }
  return parts.join(' ');
}

/** `PLANT-1 → DC-1` for a link of this pane, or its ids where a name is missing. */
export function linkLabel(link: LinkEnds, names: ReadonlyMap<Id, string>): string {
  const source = names.get(link.sourceNodeId) ?? `#${link.sourceNodeId}`;
  const target = names.get(link.targetNodeId) ?? `#${link.targetNodeId}`;
  return `${source} → ${target}`;
}

/**
 * How many panes hold the selected element - the window's one-line readout.
 *
 * `of` counts every pane that has *loaded a structure*, not every pane on screen: a pane still
 * reading its nodes has no answer yet, and counting it as an absence would make "in 3 of 4" flick to
 * "in 3 of 3" as the fourth arrives, which reads as the number changing its mind.
 */
export interface MatchTally {
  readonly present: number;
  readonly of: number;
}

export function tallyMatches(present: readonly boolean[]): MatchTally {
  return { present: present.filter(Boolean).length, of: present.length };
}

function equalIgnoringCase(a: string, b: string): boolean {
  return a.toLocaleLowerCase() === b.toLocaleLowerCase();
}
