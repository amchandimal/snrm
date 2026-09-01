import { Audited, Id } from './common.model';
import { Duration } from './time.model';

/**
 * Disruption scenario DTOs - CRUD `/projects/{id}/scenarios` and `/scenarios/{id}/events`
 * (FR-05).
 *
 * Mirrors `DisruptionScenarioDto`, `DisruptionEventDto` and their request records on the backend.
 *
 * Two things here are load-bearing for the scenario builder.
 *
 * **A scenario is project-scoped; an event is authored against a network.** The scenario names no
 * network - that is what lets one disruption story be replayed against every configuration variant
 * - but an event's target is a node id, a link id or a region tag, and its window has to
 * fit inside a horizon. So every event write carries a `networkId` query parameter naming the
 * network it is being written against, and the server does not store it.
 *
 * **The timing is unit-bearing** (FR-13). `startOffset` and `duration` are `{value, unit}`
 * durations measured from the start of the horizon, not counts of periods: "period 4" would be a
 * different moment in a variant stepping in hours than in one stepping in weeks, and the comparison
 * the scenario exists to support would be measuring the units. The timeline positions its bars by
 * the converted period value and labels them with the declared unit.
 */

/** What a disruption event targets. */
export const DisruptionTargetType = {
  /** A single node, identified by `targetId`. */
  NODE: 'NODE',
  /** A single link, identified by `targetId`. */
  LINK: 'LINK',
  /**
   * Every node carrying a given region tag, named by `targetRegion` - how correlated geographic
   * disruptions are expressed.
   *
   * Which nodes that is depends on the network, not on the scenario: ask
   * `GET /networks/{id}/region-nodes?region=…`, which the builder previews before saving.
   */
  REGION: 'REGION',
} as const;

export type DisruptionTargetType = (typeof DisruptionTargetType)[keyof typeof DisruptionTargetType];

/** Every target type, in the order the target picker offers them. */
export const DISRUPTION_TARGET_TYPES: readonly DisruptionTargetType[] = [
  DisruptionTargetType.NODE,
  DisruptionTargetType.LINK,
  DisruptionTargetType.REGION,
];

/** How availability returns after a disruption, parameterised by duration. */
export const RecoveryProfileType = {
  /** Availability stays at the disrupted level for the whole duration, then snaps back. */
  STEP: 'STEP',
  /** Availability ramps back linearly across the recovery window. */
  LINEAR: 'LINEAR',
  /** Availability approaches nominal asymptotically. */
  EXPONENTIAL: 'EXPONENTIAL',
} as const;

export type RecoveryProfileType = (typeof RecoveryProfileType)[keyof typeof RecoveryProfileType];

export const RECOVERY_PROFILES: readonly RecoveryProfileType[] = [
  RecoveryProfileType.STEP,
  RecoveryProfileType.LINEAR,
  RecoveryProfileType.EXPONENTIAL,
];

/** One line per profile for the editor's dropdown - the choice changes the recovery curve. */
export const RECOVERY_PROFILE_HINT: Readonly<Record<RecoveryProfileType, string>> = {
  STEP: 'Held at the disrupted level for the whole window, then restored at once.',
  LINEAR: 'Ramps back evenly across the window.',
  EXPONENTIAL: 'Recovers fast at first, then approaches nominal asymptotically.',
};

/** A named set of disruption events plus its Monte Carlo settings. */
export interface DisruptionScenario extends Audited {
  readonly id: Id;
  readonly projectId: Id;
  readonly name: string;
  /** Monte Carlo replications, default 100. */
  readonly numReplications: number;
  /** RNG seed, or null to draw one per run; the seed used is recorded on the run either way. */
  readonly seed: number | null;
  readonly eventCount: number;
  /**
   * Present on `GET /scenarios/{id}`, absent from the list response.
   *
   * Earliest start offset first - ordered server-side by the derived second count, which is the only
   * ordering that is correct once two events in one scenario are written in different units.
   */
  readonly events?: readonly DisruptionEvent[];
}

/** One disruption within a scenario - a bar on the scenario builder timeline. */
export interface DisruptionEvent extends Audited {
  readonly id: Id;
  readonly scenarioId: Id;
  readonly targetType: DisruptionTargetType;
  /** Node or link id; null for a REGION event. */
  readonly targetId: Id | null;
  /** The `node.region` tag; null for a NODE or LINK event. */
  readonly targetRegion: string | null;
  /** How far into the run the disruption begins, from the start of the horizon. */
  readonly startOffset: Duration;
  /** How long it lasts; also parameterises the recovery profile. */
  readonly duration: Duration;
  /** Capacity-availability multiplier reduction, in [0,1]. */
  readonly severity: number;
  readonly recoveryProfile: RecoveryProfileType;
  /** Probability the event occurs at all in a given replication. */
  readonly probability: number;
}

/** Client-supplied fields of a scenario. */
export interface DisruptionScenarioRequest {
  readonly name: string;
  /** Omit for the 100. */
  readonly numReplications?: number;
  /** Omit, or send null, to draw a fresh seed per run. */
  readonly seed?: number | null;
}

/** Options for `POST /scenarios/{id}/duplicate`. The whole body may be omitted. */
export interface DuplicateScenarioRequest {
  /** Omit for `<name> (copy)`. */
  readonly name?: string;
}

/**
 * Client-supplied fields of a disruption event.
 *
 * Sent with `?networkId=` - the network the event is authored against. The server resolves the
 * target in it and measures the window against its clock, then throws both away; the scenario stays
 * project-scoped.
 */
export interface DisruptionEventRequest {
  readonly targetType: DisruptionTargetType;
  /** Required for NODE and LINK; must be absent for REGION. */
  readonly targetId?: Id | null;
  /** Required for REGION; must be absent for NODE and LINK. */
  readonly targetRegion?: string | null;
  readonly startOffset: Duration;
  readonly duration: Duration;
  /** In [0,1]. */
  readonly severity: number;
  /** Omit for STEP. */
  readonly recoveryProfile?: RecoveryProfileType;
  /** In [0,1]. Omit for 1.0 - a deterministic event. */
  readonly probability?: number;
}

// ------------------------------------------------------------------------------ regions

/** One region tag in use in a network, and how many nodes carry it. */
export interface Region {
  readonly region: string;
  readonly nodeCount: number;
}

/**
 * `GET /networks/{id}/region-nodes?region=…` - what a REGION event would actually strike.
 *
 * An empty `nodes` is a valid answer, not an error: the tag may be one the scenario picked up
 * against another variant. Saving such an event is refused with `EVENT_TARGET_INVALID`; previewing
 * it is how the builder warns first.
 */
export interface RegionNodes {
  readonly networkId: Id;
  readonly region: string;
  readonly nodeCount: number;
  /** Ids and names of the matched nodes, in id order. Shape mirrors `NetworkNode`. */
  readonly nodes: readonly RegionNode[];
}

/** The fields of a matched node the preview shows. The server sends the full node DTO. */
export interface RegionNode {
  readonly id: Id;
  readonly name: string;
  readonly type: string;
  readonly region: string | null;
}
