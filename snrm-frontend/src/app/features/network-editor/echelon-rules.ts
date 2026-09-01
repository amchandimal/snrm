import {
  Duration,
  LinkRequest,
  NetworkLink,
  NodeRequest,
  NodeType,
  Rate,
  TimeUnit,
} from '../../core/models';

/**
 * Echelon rules, type defaults and palette presentation for the network editor.
 *
 * Deliberately free of Angular, Cytoscape and HTTP: link validity is the one piece of editor logic
 * that has to be right, and pure functions are the only version of it that can be unit-tested
 * exhaustively. `echelon-rules.spec.ts` walks every row of the table below.
 */

/**
 * Position of each echelon in the canonical flow SUPPLIER → PLANT → DC → CUSTOMER.
 *
 * The rank is what makes "unconventional" decidable: a link that advances exactly one rank is the
 * textbook case, and everything else that is not outright blocked earns the warning badge the
 * editor shows.
 */
export const ECHELON_RANK: Readonly<Record<NodeType, number>> = {
  SUPPLIER: 0,
  PLANT: 1,
  DC: 2,
  CUSTOMER: 3,
};

/** Why a link was allowed but flagged. Rendered as the warning badge. */
export const LinkWarning = {
  /** Same echelon on both ends - the DC → DC lateral transshipment. */
  LATERAL: 'Lateral link within the same echelon (e.g. DC-to-DC transshipment).',
  /** Points back up the chain, e.g. DC → PLANT: a return or repair flow. */
  UPSTREAM: 'Runs upstream, against the usual echelon order.',
  /** Jumps a tier, e.g. PLANT → CUSTOMER: direct shipment bypassing distribution. */
  SKIPS_ECHELON: 'Skips an echelon.',
} as const;

export type LinkWarning = (typeof LinkWarning)[keyof typeof LinkWarning];

/** Why a link was refused at the gesture level. Shown on the red target highlight. */
export const LinkRefusal = {
  SELF_LOOP: 'A node cannot link to itself.',
  DUPLICATE: 'These nodes are already connected in that direction.',
  INTO_SUPPLIER: 'A SUPPLIER is a source and has no inbound links.',
  OUT_OF_CUSTOMER: 'A CUSTOMER is a sink and has no outbound links.',
} as const;

export type LinkRefusal = (typeof LinkRefusal)[keyof typeof LinkRefusal];

/** The answer to "may this arc be drawn, and should it be flagged?" */
export type LinkVerdict =
  | { readonly ok: true; readonly warning?: LinkWarning }
  | { readonly ok: false; readonly reason: LinkRefusal };

/** One end of a candidate link, as much of it as the rules need. */
export interface LinkEndpoint {
  readonly id: number;
  readonly type: NodeType;
}

/**
 * Whether an arc from `source` to `target` may be created.
 *
 * There are exactly two echelon refusals - into a SUPPLIER, out of a CUSTOMER - plus the two
 * structural ones that have to be caught at the gesture level, self-loops and duplicates.
 * Everything else is permitted: `LinkController` makes the same choice for the same reason, that a
 * research tool refusing to model an unconventional topology is refusing the experiment.
 *
 * Evaluation order matters. Self-loop and duplicate come first because they are true regardless of
 * echelon, so their message is the more specific one to show.
 *
 * @param existingPairs ordered `sourceId->targetId` keys already present; see {@link linkPairKey}
 */
export function linkVerdict(
  source: LinkEndpoint,
  target: LinkEndpoint,
  existingPairs: ReadonlySet<string>,
): LinkVerdict {
  if (source.id === target.id) {
    return { ok: false, reason: LinkRefusal.SELF_LOOP };
  }
  if (existingPairs.has(linkPairKey(source.id, target.id))) {
    return { ok: false, reason: LinkRefusal.DUPLICATE };
  }
  // CUSTOMER → SUPPLIER trips both echelon rules. The source one is reported because it describes
  // the end the user dragged *from*, which is the one they can see while the pointer is moving.
  if (source.type === 'CUSTOMER') {
    return { ok: false, reason: LinkRefusal.OUT_OF_CUSTOMER };
  }
  if (target.type === 'SUPPLIER') {
    return { ok: false, reason: LinkRefusal.INTO_SUPPLIER };
  }

  const step = ECHELON_RANK[target.type] - ECHELON_RANK[source.type];
  if (step === 1) {
    return { ok: true };
  }
  if (step === 0) {
    return { ok: true, warning: LinkWarning.LATERAL };
  }
  if (step < 0) {
    return { ok: true, warning: LinkWarning.UPSTREAM };
  }
  return { ok: true, warning: LinkWarning.SKIPS_ECHELON };
}

/**
 * Key for the ordered pair `(sourceId, targetId)`.
 *
 * Ordered, not symmetric: the network model and `LinkController` both treat A→B and B→A as different
 * arcs that may both exist, so a symmetric key would refuse a legal return flow.
 */
export function linkPairKey(sourceNodeId: number, targetNodeId: number): string {
  return `${sourceNodeId}->${targetNodeId}`;
}

/** Every ordered pair currently connected, for {@link linkVerdict}'s duplicate check. */
export function linkPairKeys(links: Iterable<NetworkLink>): ReadonlySet<string> {
  const keys = new Set<string>();
  for (const link of links) {
    keys.add(linkPairKey(link.sourceNodeId, link.targetNodeId));
  }
  return keys;
}

/** The warning a link that already exists carries, or null when it is a textbook forward arc. */
export function warningForLink(source: NodeType, target: NodeType): LinkWarning | null {
  const step = ECHELON_RANK[target] - ECHELON_RANK[source];
  if (step === 1) {
    return null;
  }
  if (step === 0) {
    return LinkWarning.LATERAL;
  }
  return step < 0 ? LinkWarning.UPSTREAM : LinkWarning.SKIPS_ECHELON;
}

// ---------------------------------------------------------------- presentation and defaults

/** Everything the palette tile, the canvas style and the create gesture need for one node type. */
export interface NodeTypeProfile {
  readonly type: NodeType;
  readonly label: string;
  /** Fill on the canvas and on the palette tile. */
  readonly colour: string;
  /** Border and text colour, darkened from {@link colour} for contrast. */
  readonly accent: string;
  /** Cytoscape node shape - a second, colour-blind-safe channel for the type. */
  readonly shape: 'round-rectangle' | 'ellipse' | 'diamond' | 'hexagon';
  /** Stem of the auto-generated name a dropped node gets, before its number. */
  readonly namePrefix: string;
  /** One line for the palette tile's title attribute. */
  readonly hint: string;
}

/**
 * The palette, in echelon order ("a fixed palette on the left lists the four node types").
 *
 * `NODE_TYPES` in `core/models/node.model.ts` already fixes that order; this keeps to it.
 */
export const NODE_TYPE_PROFILES: readonly NodeTypeProfile[] = [
  {
    type: 'SUPPLIER',
    label: 'Supplier',
    colour: '#5b7fa6',
    accent: '#33506d',
    shape: 'round-rectangle',
    namePrefix: 'Supplier',
    hint: 'Upstream source of material. Has no inbound links.',
  },
  {
    type: 'PLANT',
    label: 'Plant',
    colour: '#6f5bb0',
    accent: '#463674',
    shape: 'hexagon',
    namePrefix: 'Plant',
    hint: 'Production site. Transforms inbound material into outbound product.',
  },
  {
    type: 'DC',
    label: 'DC',
    colour: '#2f8f83',
    accent: '#1c5c54',
    shape: 'ellipse',
    namePrefix: 'DC',
    hint: 'Distribution centre. Stocks and forwards; may transship laterally.',
  },
  {
    type: 'CUSTOMER',
    label: 'Customer',
    colour: '#c1873a',
    accent: '#855a1e',
    shape: 'diamond',
    namePrefix: 'Customer',
    hint: 'Demand sink. Carries demand per product and has no outbound links.',
  },
];

const PROFILE_BY_TYPE: ReadonlyMap<NodeType, NodeTypeProfile> = new Map(
  NODE_TYPE_PROFILES.map((profile) => [profile.type, profile]),
);

export function nodeTypeProfile(type: NodeType): NodeTypeProfile {
  const profile = PROFILE_BY_TYPE.get(type);
  if (!profile) {
    throw new Error(`No palette profile for node type ${type}`);
  }
  return profile;
}

/**
 * Attribute defaults for a node created by the drop gesture.
 *
 * These match the column defaults of `V2__domain.sql`, which `NodeRequest` also relies on: a node
 * dropped on the canvas is valid with nothing but a name and a type, and every figure the researcher
 * has not chosen yet reads as zero rather than as a number they might mistake for data.
 *
 * The two unit-bearing fields are stated rather than omitted, so the node comes back carrying the
 * units the panel will open on: no dwell, in the network's period unit, and an unconstrained
 * capacity over whichever unit this researcher has been using for capacities.
 *
 * @param periodUnit    the network's period unit - the default for every duration
 * @param capacityUnit  the unit last picked for a node capacity, or the period unit
 */
export function defaultNodeRequest(
  type: NodeType,
  name: string,
  posX: number,
  posY: number,
  periodUnit: TimeUnit,
  capacityUnit: TimeUnit = periodUnit,
): NodeRequest {
  const capacity: Rate = { value: null, timeUnit: capacityUnit };
  const processingTime: Duration = { value: 0, unit: periodUnit };
  return {
    name,
    type,
    capacity,
    processingTime,
    fixedCost: 0,
    varCost: 0,
    failureProb: 0,
    region: null,
    lat: null,
    lng: null,
    posX,
    posY,
  };
}

/**
 * Attribute defaults for a link created by the edge-handle gesture.
 *
 * The lead time is **one period**, expressed as the network's own period length - 1 DAY on a daily
 * network, 2 HOUR on a two-hourly one. Before units it was the bare number 1 for the same reason: an
 * arc that delivers in the period it ships is a degenerate special case, and the property panel
 * opens on creation so the figure is visible and editable immediately. Stating it as a duration
 * keeps that intent true whatever the clock says, and it converts exactly by construction.
 *
 * @param periodLength the network's period, used verbatim as the default transit time
 * @param capacityUnit the unit last picked for a link capacity, or the period unit
 */
export function defaultLinkRequest(
  sourceNodeId: number,
  targetNodeId: number,
  periodLength: Duration,
  capacityUnit: TimeUnit = periodLength.unit,
): LinkRequest {
  return {
    sourceNodeId,
    targetNodeId,
    leadTime: { value: periodLength.value, unit: periodLength.unit },
    capacity: { value: null, timeUnit: capacityUnit },
    unitCost: 0,
    failureProb: 0,
  };
}

/**
 * A name of the form `Supplier 3` that no node in `taken` holds.
 *
 * Node names are unique per network (the importer resolves link endpoints by them), so the drop
 * gesture cannot just use the type label - the second supplier would come back as a 409.
 */
export function nextNodeName(type: NodeType, taken: Iterable<string>): string {
  const prefix = nodeTypeProfile(type).namePrefix;
  const used = new Set<string>();
  for (const name of taken) {
    used.add(name.toLocaleLowerCase());
  }
  for (let n = 1; ; n++) {
    const candidate = `${prefix} ${n}`;
    if (!used.has(candidate.toLocaleLowerCase())) {
      return candidate;
    }
  }
}
