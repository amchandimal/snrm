import { Id } from './common.model';
import { TimeUnit } from './time.model';

/**
 * Metric DTOs - `GET /networks/{id}/metrics/topological` and the metric half of
 * `GET /simulations/{runId}/results`.
 *
 * The topological half is implemented: `MetricResult` and `TopologicalMetricsResponse` mirror the
 * backend DTOs of the same names. The simulated half is still provisional - the run endpoints
 * do not exist yet - but nothing about the shape differs, which is the point of
 * `METRIC_RESULT` being a narrow key–value table: a new metric adds a row, never a column, and
 * therefore never a type change here.
 */

/** What a metric value is attached to. */
export const MetricScope = {
  /** Whole-network value; `scopeId` is null. */
  NETWORK: 'NETWORK',
  /** Per-node value; `scopeId` is a node id. */
  NODE: 'NODE',
  /** Per-link value; `scopeId` is a link id. */
  LINK: 'LINK',
} as const;

export type MetricScope = (typeof MetricScope)[keyof typeof MetricScope];

/** Whether a metric comes from the graph or from simulation traces. */
export const MetricKind = {
  /** Computed synchronously on the graph snapshot on save/edit. */
  TOPOLOGICAL: 'TOPOLOGICAL',
  /** Computed once per simulation job, aggregated across replications. */
  SIMULATED: 'SIMULATED',
} as const;

export type MetricKind = (typeof MetricKind)[keyof typeof MetricKind];

/**
 * The metric suite, spanning the six families the SLR found fragmented (RQ5).
 *
 * A string union rather than a closed enum: the backend's registry discovers calculators at
 * runtime, so an unknown code is a new metric to display generically, not an error.
 */
export const MetricCode = {
  // Topological / structural, in suite order
  DENSITY: 'DENSITY',
  /** Nodes whose single removal strands a customer - the facility half of the SPOF census. */
  SPOF_NODE_COUNT: 'SPOF_NODE_COUNT',
  /** Arcs whose single removal strands a customer - the lane half. */
  SPOF_ARC_COUNT: 'SPOF_ARC_COUNT',
  /** Both together; the headline figure, and by construction the sum of the two above. */
  SPOF_COUNT: 'SPOF_COUNT',
  AVG_PATH: 'AVG_PATH',
  CLUSTERING: 'CLUSTERING',
  NODE_CRITICALITY: 'NODE_CRITICALITY',
  ROBUSTNESS_RANDOM: 'ROBUSTNESS_RANDOM',
  ROBUSTNESS_TARGETED: 'ROBUSTNESS_TARGETED',
  // Service
  FILL_RATE: 'FILL_RATE',
  SERVICE_LEVEL: 'SERVICE_LEVEL',
  // Recovery
  TTR: 'TTR',
  // Absorption / robustness
  MIN_FILL_RATE: 'MIN_FILL_RATE',
  LOSS_AREA: 'LOSS_AREA',
  CVAR_COST: 'CVAR_COST',
  /** Mean end-of-period on-hand stock across the horizon (FR-19). NEUTRAL. */
  AVG_INVENTORY: 'AVG_INVENTORY',
  /** Mean in-transit quantity across the horizon (FR-19). NEUTRAL. */
  AVG_PIPELINE: 'AVG_PIPELINE',
  // Economic
  TOTAL_COST: 'TOTAL_COST',
  DISRUPTION_COST_DELTA: 'DISRUPTION_COST_DELTA',
  // Composite
  RESILIENCE_INDEX: 'RESILIENCE_INDEX',
} as const;

export type MetricCode = (typeof MetricCode)[keyof typeof MetricCode] | (string & {});

/**
 * The nine topological codes, in the order the backend's calculator registry emits them.
 *
 * A list rather than a filter predicate: a metrics panel has to lay the network-scoped values out in
 * a stable order, and "whatever order the response arrived in" is stable only until someone changes
 * an `@Order` annotation.
 *
 * **The order leads with the density and the three single-point-of-failure figures**, because those
 * are what a researcher judges a configuration on before anything else - how connected it is, and
 * where it has no second option - and the path, clustering, criticality and robustness statistics
 * describe the shape that produced them. It is the backend's own order transcribed rather than
 * invented: the client sorts by this list so the panes of a comparison window agree with each other,
 * and the list matches the registry so those panes also agree with the comparison matrix, which is
 * ordered server-side and never passes through here.
 *
 * **This is the one place the order is written on the client.** Every surface that lists the
 * structural suite ranks against it - the editor's metrics panel and the network dashboard's
 * structure block through `TopologicalMetricsStore.networkMetrics`, and each comparison pane through
 * `SideBySideStore` - because one researcher looking at one network on two of those screens must not
 * meet the same nine numbers in two orders.
 */
export const TOPOLOGICAL_METRIC_CODES: readonly MetricCode[] = [
  MetricCode.DENSITY,
  MetricCode.SPOF_NODE_COUNT,
  MetricCode.SPOF_ARC_COUNT,
  MetricCode.SPOF_COUNT,
  MetricCode.AVG_PATH,
  MetricCode.CLUSTERING,
  MetricCode.NODE_CRITICALITY,
  MetricCode.ROBUSTNESS_RANDOM,
  MetricCode.ROBUSTNESS_TARGETED,
];

/**
 * One metric value.
 *
 * Confidence bounds are the 95% CI across Monte Carlo replications and are absent on
 * topological metrics, which are exact.
 */
export interface MetricResult {
  readonly id?: Id;
  readonly networkId: Id;
  /** Null for topological metrics - they belong to the network, not a run. */
  readonly runId: Id | null;
  readonly metricCode: MetricCode;
  readonly scope: MetricScope;
  /** Node or link id for scoped metrics; null at NETWORK scope. */
  readonly scopeId: Id | null;
  /**
   * Name of the node or link `scopeId` points at; null at NETWORK scope.
   *
   * Derived server-side from the graph snapshot the value was computed on, so a per-node table can
   * be rendered from this response alone rather than joined against a separately fetched node list.
   */
  readonly scopeName: string | null;
  readonly value: number;
  readonly ciLow: number | null;
  readonly ciHigh: number | null;
  /** Unit `value` is expressed over for a time-valued metric such as TTR; null otherwise. */
  readonly displayUnit: TimeUnit | null;
}

/** Response of `GET /networks/{id}/metrics/topological` - computed, returned and persisted. */
export interface TopologicalMetricsResponse {
  readonly networkId: Id;
  readonly metrics: readonly MetricResult[];
  /** Wall-clock cost of the computation; FR-04 budgets 2 s at 1,000 nodes. */
  readonly computedInMs: number;
}
