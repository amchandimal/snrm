import { Id, IsoInstant } from './common.model';
import { LeverChanges, VariantOrigin } from './network.model';
import { MetricCode } from './metric.model';
import { Duration, TimeUnit } from './time.model';

/**
 * Configuration-comparison DTOs - `GET /projects/{id}/comparison?networkIds=…` (FR-10).
 *
 * Mirrors the backend's `ComparisonMatrixDto` and its parts. Three things the server decides that a
 * client might be tempted to redo, and must not:
 *
 * 1. **The winner of each row.** Which way is better is the calculator's own declaration,
 *    and ties are common in a suite full of bounded ratios. Deciding it once is what stops the
 *    matrix on screen and the matrix in an exported spreadsheet from disagreeing.
 * 2. **The common unit.** A time-valued metric is a count of *its own network's* periods, so
 *    columns clocked differently hold numbers on different scales. The server converts them;
 *    the conversion needs each variant's period length, which it has and a client
 *    would have to fetch per column.
 * 3. **The caveats.** {@link ComparisonMatrix.mixedTimeBases} and
 *    {@link ComparisonMatrix.mixedScenarios} are the two ways a comparison can be arithmetically
 *    correct and still misleading, and neither is visible in the numbers.
 */

/** Which way is better, for best-in-row highlighting. Mirrors the backend enum. */
export const MetricDirection = {
  /** A larger value is a better configuration - `FILL_RATE`, `RESILIENCE_INDEX`. */
  HIGHER_IS_BETTER: 'HIGHER_IS_BETTER',
  /** A smaller value is a better configuration - `TTR`, `TOTAL_COST`. */
  LOWER_IS_BETTER: 'LOWER_IS_BETTER',
  /**
   * The metric describes the configuration without ranking it - `DENSITY`, `AVG_PATH`,
   * `CLUSTERING`. No cell is highlighted, and that is a decision rather than a gap: a denser
   * network is better connected *and* more expensive, and neither is an objective of the model.
   */
  NEUTRAL: 'NEUTRAL',
} as const;

export type MetricDirection = (typeof MetricDirection)[keyof typeof MetricDirection];

/** One column of the matrix: a configuration, and what was changed to make it. */
export interface ComparisonVariant {
  readonly networkId: Id;
  readonly name: string;
  readonly version: number;
  readonly baseline: boolean;
  /** The network this was forked from; null for a baseline or an imported network. */
  readonly baseNetworkId: Id | null;
  /** `MANUAL` for an editor fork, `SEARCH` for a Phase 2 candidate. Null if not a variant. */
  readonly generatedBy: VariantOrigin | null;
  /**
   * The structured diff from {@link baseNetworkId} - the lever-change annotation.
   *
   * This is what turns the matrix from a scoreboard into a finding: a column headed "Baseline v3"
   * reports that something improved, a column headed "+20% capacity at PLANT-1" reports what
   * improved it. Null for a baseline, which was forked from nothing.
   */
  readonly leverChanges: LeverChanges | null;
  /**
   * The completed run the simulated cells were read from.
   *
   * Null for a network that has never been simulated - a legitimate column, filling only the
   * topological half of the suite, since structural metrics are computed on save.
   */
  readonly runId: Id | null;
  readonly scenarioId: Id | null;
  readonly scenarioName: string | null;
  readonly runFinishedAt: IsoInstant | null;
  /** This configuration's clock. Columns whose period lengths differ raise `mixedTimeBases`. */
  readonly periodLength: Duration;
  readonly horizonPeriods: number;
}

/**
 * One cell: what one variant scored on one metric.
 *
 * A cell is `null` in {@link ComparisonRow.cells} where the variant has no value - a variant with
 * no completed run, or a metric undefined for its run (`TTR` has nothing to report if no
 * replication was disrupted). **Null is unmeasured, never zero**: rendering it as 0 would put the
 * best possible recovery time on a configuration nobody tested.
 */
export interface ComparisonCell {
  /** The number, in {@link ComparisonRow.unit} where the row is time-valued. */
  readonly value: number;
  readonly ciLow: number | null;
  readonly ciHigh: number | null;
  /**
   * The raw value in *this* variant's own periods, before conversion; null for a dimensionless
   * metric.
   *
   * Both numbers are needed and neither substitutes for the other: the converted value makes two
   * variants comparable when their clocks differ, and the period count ties the figure back to the
   * run a reader can open.
   */
  readonly periods: number | null;
  /** True for the best cell in the row, or every cell tied for it. Always false in a NEUTRAL row. */
  readonly best: boolean;
}

/** One row of the matrix: one metric across every variant, aligned by index with the columns. */
export interface ComparisonRow {
  readonly metricCode: MetricCode;
  readonly direction: MetricDirection;
  /** Whether the value is a count of periods and so needs a unit to be read. */
  readonly timeValued: boolean;
  /** The common unit every cell was converted to; null unless {@link timeValued}. */
  readonly unit: TimeUnit | null;
  /** One entry per variant, in the order of {@link ComparisonMatrix.variants}; null where absent. */
  readonly cells: readonly (ComparisonCell | null)[];
}

/** A caveat about the whole comparison - shown above the matrix, and exported with it. */
export interface ComparisonNote {
  /** `MIXED_TIME_BASES`, `MIXED_SCENARIOS`, `NO_RUN` or `PARTIAL_SUITE`. */
  readonly code: string;
  readonly message: string;
}

/** The cross-variant metric matrix behind the comparison view. */
export interface ComparisonMatrix {
  readonly projectId: Id;
  /** The columns, in the order the request named them. */
  readonly variants: readonly ComparisonVariant[];
  /** The rows, in suite order - topological first, then simulated. */
  readonly rows: readonly ComparisonRow[];
  /** The unit every time-valued row was converted to; null when no compared metric is time-valued. */
  readonly timeUnit: TimeUnit | null;
  /**
   * The compared configurations do not share a period length.
   *
   * The values are still comparable - they have been converted - but the models differ in what they
   * can resolve, which is the same argument that applies at network scale. The view must say so.
   */
  readonly mixedTimeBases: boolean;
  /** The runs behind the columns applied different scenarios, so the comparison measures those too. */
  readonly mixedScenarios: boolean;
  readonly notes: readonly ComparisonNote[];
}
