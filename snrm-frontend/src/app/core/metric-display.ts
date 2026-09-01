import { MetricCode } from './models/metric.model';
import { Duration, TimeUnit } from './models/time.model';
import { formatNumber, roundForDisplay } from './time-units';

/**
 * How a metric of the suite is read: what it is called, what family it belongs to, what its
 * number means, and - for the time-valued ones - what unit that number is in.
 *
 * Free of Angular and of HTTP, like `core/time-units.ts` and `features/scenario-builder/timeline.ts`.
 * The reason is the same in all three: the conversion is the part that has to be right, and a pure
 * function is the only version of it that can be tested exhaustively.
 *
 * **Nothing here decides which way is better.** That is the calculator's own declaration, delivered
 * on `ComparisonRow.direction`, and a copy of it in the client would be a second list to
 * fall out of step with the registry the moment a metric is added. The dashboard's metric cards do
 * not rank anything, so they never need it; the comparison matrix reads the server's answer.
 */

// ------------------------------------------------------------------- periods and units

/**
 * A count of this network's periods, restated as a length of time.
 *
 * ```text
 * readable = periods × periodLength.value , in periodLength.unit
 * ```
 *
 * **The multiplication by `periodLength.value` is the whole point of this function.** A metric's
 * `displayUnit` names the unit a period is *stated in*, not how long a period is: a network stepping
 * in `2 DAY` carries `displayUnit: DAY`, and 14 of its periods are 28 days, not 14. Reading the unit
 * without the value is the one slip the reporting rule exists to prevent, and it is silent
 * - the number looks perfectly reasonable.
 *
 * Networks whose period value is 1 - which is most of them - get `periods` back unchanged, which is
 * why the mistake survives so easily in testing.
 */
export function readablePeriods(periods: number, periodLength: Duration): Duration {
  return {
    value: roundForDisplay(periods * periodLength.value),
    unit: periodLength.unit,
  };
}

/**
 * A time-valued metric written out in full: `14 periods (14 days)`.
 *
 * > "results read as 'TTR = 14 periods (14 days)' rather than a bare number"
 *
 * Both halves are kept because neither substitutes for the other. The period count is what ties the
 * figure to the run - period 14 is a column in the timeseries a reader can go and look at - and the
 * readable duration is what makes it mean something to anyone who does not hold this network's clock
 * in their head.
 */
export function formatTimeValued(periods: number, periodLength: Duration): string {
  const readable = readablePeriods(periods, periodLength);
  return `${formatNumber(periods)} ${plural('period', periods)} (${formatUnits(readable)})`;
}

/**
 * A duration as prose - `14 days`, `1 day`, `6 hours`.
 *
 * Its own noun table rather than `TIME_UNIT_LABEL`, which is written for dropdown options and
 * carries the fixed-length caveats inline (`months (30 d)`). Those belong beside a unit the user is
 * *choosing*; nested inside a metric reading they would produce "14 periods (3 months (30 d))".
 */
export function formatUnits(duration: Duration): string {
  return `${formatNumber(duration.value)} ${plural(UNIT_NOUN[duration.unit], duration.value)}`;
}

const UNIT_NOUN: Readonly<Record<TimeUnit, string>> = {
  SECOND: 'second',
  MINUTE: 'minute',
  HOUR: 'hour',
  DAY: 'day',
  WEEK: 'week',
  MONTH: 'month',
  YEAR: 'year',
};

/**
 * Where a transport stands in a run: `Period 14 of 52 - 14 days` (FR-18, FR-22).
 *
 * > "with the clock read as 'Period 14 of 52 - 14 days' - restated through the run's own period
 * > length, never the live network's"
 *
 * Both halves for the reason {@link formatTimeValued} keeps both of its: the index is what ties the
 * position to a column of the run's series a reader can go and look at, and the duration is what
 * makes it mean anything to someone who does not hold this network's clock in their head. The
 * restatement goes through {@link readablePeriods}, so the multiplication by the period's *value*
 * happens here too - a run stepping in `2 DAY` is at 28 days in period 14.
 *
 * **Two surfaces print this string**: the editor's playback transport, where the position is a
 * *clock* advancing itself, and the results dashboard's period cursor, where it is a position a
 * reader moves (the dashboard navigates a run, it does not animate one). What they share
 * is not the mechanism but the reading, and the reading has to be identical: one researcher looks at
 * period 14 of one run on both screens, and two phrasings of that would read as two different
 * numbers. It is therefore here rather than in either feature, on the rule
 * `core/lever-changes.ts` and `core/run-discard.ts` already state.
 *
 * @param horizonPeriods how many periods the run has - the "of 52", a **count** and not the last
 *     index, so a 52-period run's final position reads "Period 51 of 52"
 * @param periodLength the **run's** clock, or null where it has none recorded - then the index alone
 *     is printed rather than a duration invented for it
 */
export function periodReadout(
  period: number,
  horizonPeriods: number,
  periodLength: Duration | null,
): string {
  const position = `Period ${formatNumber(period)} of ${formatNumber(horizonPeriods)}`;
  return periodLength === null
    ? position
    : `${position} - ${formatUnits(readablePeriods(period, periodLength))}`;
}

/**
 * The x-axis label of the performance curve - `Period (1 day)`, `Period (6 hours)`.
 *
 * The curve's x-axis is a period index, and a period index is meaningless without the period:
 * the same 52-point curve is a year on one network and two days on another. Naming the
 * unit on the axis is the cheapest place to say so.
 */
export function periodAxisLabel(periodLength: Duration): string {
  return `Period (${formatUnits(periodLength)})`;
}

/** `14 days` - a converted comparison value in the row's common unit. */
export function formatInUnit(value: number, unit: TimeUnit): string {
  return formatUnits({ value: roundForDisplay(value), unit });
}

function plural(word: string, count: number): string {
  return count === 1 ? word : `${word}s`;
}

// -------------------------------------------------------------------------- descriptors

/** The six metric families (RQ5), which is what the badge colour encodes. */
export const MetricFamily = {
  TOPOLOGICAL: 'TOPOLOGICAL',
  SERVICE: 'SERVICE',
  RECOVERY: 'RECOVERY',
  ABSORPTION: 'ABSORPTION',
  ECONOMIC: 'ECONOMIC',
  COMPOSITE: 'COMPOSITE',
  /** A code this build has never seen. Rendered plainly rather than hidden. */
  UNKNOWN: 'UNKNOWN',
} as const;

export type MetricFamily = (typeof MetricFamily)[keyof typeof MetricFamily];

export const METRIC_FAMILY_LABEL: Readonly<Record<MetricFamily, string>> = {
  TOPOLOGICAL: 'Structural',
  SERVICE: 'Service',
  RECOVERY: 'Recovery',
  ABSORPTION: 'Absorption',
  ECONOMIC: 'Economic',
  COMPOSITE: 'Composite',
  UNKNOWN: 'Other',
};

/** How a value should be written. */
export type MetricFormat =
  /** A number in [0,1] shown as a percentage - a fill rate, a service level. */
  | 'ratio'
  /** A bare ratio kept as a decimal - a density, a clustering coefficient. */
  | 'decimal'
  /** A whole number - an SPOF count. */
  | 'count'
  /** Money. */
  | 'currency'
  /** Hops. */
  | 'hops'
  /** A count of periods, which needs the network's clock to be read. */
  | 'periods'
  /** Fill-rate × periods: the area of the resilience triangle (`LOSS_AREA`). */
  | 'area';

export interface MetricDescriptor {
  readonly label: string;
  readonly family: MetricFamily;
  readonly format: MetricFormat;
  /** One sentence from the definition. A bare number is not a finding. */
  readonly meaning: string;
}

/**
 * The suite, described.
 *
 * A lookup with a fallback rather than a closed map: the backend's registry discovers calculators at
 * runtime, so a code this build has never seen is a metric someone added, and rendering
 * its code and value beats hiding it.
 */
export const METRIC_DESCRIPTORS: Readonly<Record<string, MetricDescriptor>> = {
  // ---- Topological / structural, in the suite order of `TOPOLOGICAL_METRIC_CODES`.
  // The order is not read from this object - a lookup has no order to read - but keeping the two
  // agreed is what stops a reader of this file inferring the wrong one.
  DENSITY: {
    label: 'Density',
    family: MetricFamily.TOPOLOGICAL,
    format: 'decimal',
    meaning: 'Of all the arcs that could exist between these nodes, the fraction that do.',
  },
  SPOF_NODE_COUNT: {
    label: 'Single points of failure - nodes',
    family: MetricFamily.TOPOLOGICAL,
    format: 'count',
    meaning:
      'Facilities whose loss alone cuts some customer off from all supply. The remedy is a second site or a second qualified source.',
  },
  SPOF_ARC_COUNT: {
    label: 'Single points of failure - arcs',
    family: MetricFamily.TOPOLOGICAL,
    format: 'count',
    meaning:
      'Lanes whose loss alone cuts some customer off from all supply. The remedy is a second route or a second carrier - the facilities are already there.',
  },
  SPOF_COUNT: {
    label: 'Single points of failure - total',
    family: MetricFamily.TOPOLOGICAL,
    format: 'count',
    meaning:
      'Nodes and arcs whose loss alone cuts some customer off from all supply. Zero is the target; the two rows above say which kind to spend on.',
  },
  AVG_PATH: {
    label: 'Average path length',
    family: MetricFamily.TOPOLOGICAL,
    format: 'hops',
    meaning: 'Mean number of arcs between a pair of nodes that are connected at all.',
  },
  CLUSTERING: {
    label: 'Clustering',
    family: MetricFamily.TOPOLOGICAL,
    format: 'decimal',
    meaning:
      'How often two partners of the same node are also connected - local alternative routes.',
  },
  NODE_CRITICALITY: {
    label: 'Node criticality',
    family: MetricFamily.TOPOLOGICAL,
    format: 'ratio',
    meaning: 'Relative drop in serviceable demand when this node is removed.',
  },
  ROBUSTNESS_RANDOM: {
    label: 'Robustness - random',
    family: MetricFamily.TOPOLOGICAL,
    format: 'decimal',
    meaning:
      'Area under the largest-component curve as nodes fail at random. Higher holds together longer; ½ is the ceiling.',
  },
  ROBUSTNESS_TARGETED: {
    label: 'Robustness - targeted',
    family: MetricFamily.TOPOLOGICAL,
    format: 'decimal',
    meaning:
      'The same curve with the most critical nodes removed first. Below the random figure means the fragility is concentrated.',
  },

  // ---- Service
  FILL_RATE: {
    label: 'Fill rate',
    family: MetricFamily.SERVICE,
    format: 'ratio',
    meaning: 'Share of demand met across the horizon.',
  },
  SERVICE_LEVEL: {
    label: 'Service level',
    family: MetricFamily.SERVICE,
    format: 'ratio',
    meaning: 'Share of periods in which demand was met in full.',
  },

  // ---- Recovery
  TTR: {
    label: 'Time to recovery',
    family: MetricFamily.RECOVERY,
    format: 'periods',
    meaning:
      'Periods from disruption onset until fill rate regains its undisrupted baseline. Censored at the horizon where recovery never comes, which understates it.',
  },

  // ---- Absorption / robustness
  MIN_FILL_RATE: {
    label: 'Worst-period fill rate',
    family: MetricFamily.ABSORPTION,
    format: 'ratio',
    meaning: 'The lowest fill rate any single period reached - the depth of the triangle.',
  },
  LOSS_AREA: {
    label: 'Loss area',
    family: MetricFamily.ABSORPTION,
    format: 'area',
    meaning:
      'Area between the baseline and disrupted performance curves - the resilience triangle, as a number.',
  },
  CVAR_COST: {
    label: 'CVaR cost (α=0.95)',
    family: MetricFamily.ABSORPTION,
    format: 'currency',
    meaning:
      'Expected total cost in the worst 5% of replications. Carries no interval: it is a functional of the whole replication set, not a mean of per-replication values.',
  },
  AVG_INVENTORY: {
    label: 'Average inventory',
    family: MetricFamily.ABSORPTION,
    format: 'decimal',
    meaning:
      'Mean end-of-period stock held across the horizon - the standing buffer the configuration pays for and absorbs shocks with.',
  },
  AVG_PIPELINE: {
    label: 'Average pipeline (WIP)',
    family: MetricFamily.ABSORPTION,
    format: 'decimal',
    meaning: 'Mean in-transit quantity - material committed but not yet arrived.',
  },

  // ---- Economic
  TOTAL_COST: {
    label: 'Total cost',
    family: MetricFamily.ECONOMIC,
    format: 'currency',
    meaning: 'Total cost over the horizon - fixed, variable, transport, holding and shortage.',
  },
  DISRUPTION_COST_DELTA: {
    label: 'Disruption cost',
    family: MetricFamily.ECONOMIC,
    format: 'currency',
    meaning:
      'Cost increase attributable to the disruption, against the paired undisrupted baseline.',
  },

  // ---- Composite
  RESILIENCE_INDEX: {
    label: 'Resilience index',
    family: MetricFamily.COMPOSITE,
    format: 'ratio',
    meaning: 'Mean performance during the disruption horizon over undisrupted performance (0–1).',
  },
};

/**
 * One metric value with everything a card needs already resolved.
 *
 * The shape the results dashboard, the editor's run panel and the network dashboard all render. It
 * is here rather than beside any one of them because three copies of the same mapping is three
 * chances for the same run to read differently on three surfaces - the rule `ComparisonService`
 * follows server-side, applied to the client.
 */
export interface MetricCard {
  readonly code: MetricCode;
  readonly label: string;
  readonly meaning: string;
  readonly format: MetricFormat;
  readonly value: number;
  readonly ciLow: number | null;
  readonly ciHigh: number | null;
  /** Set only for a time-valued metric: "14 periods (14 days)". */
  readonly readable: string | null;
}

/** The subset of `MetricResult` a card is built from - so a caller need not construct a whole one. */
export interface MetricCardSource {
  readonly metricCode: MetricCode;
  readonly value: number;
  readonly ciLow: number | null;
  readonly ciHigh: number | null;
  readonly displayUnit: TimeUnit | null;
}

/**
 * A stored metric value as a card.
 *
 * `periodLength` is the **run's** clock, not the live network's: a run carries the period it was
 * evaluated against precisely so its numbers stay readable after a fork changes the network's
 * clock. Pass null where there is no run.
 *
 * **A non-null `displayUnit` is what decides that a metric counts periods**, not this build's
 * descriptor. The descriptor is a presentation guess for a code the client recognises; the server's
 * own statement has to win, so a time-valued metric added server-side reads correctly here with no
 * matching entry (calculators are discovered at runtime).
 */
export function toMetricCard(
  metric: MetricCardSource,
  periodLength: Duration | null,
): MetricCard {
  const descriptor = describeMetric(metric.metricCode);
  return {
    code: metric.metricCode,
    label: descriptor.label,
    meaning: descriptor.meaning,
    format: descriptor.format,
    value: metric.value,
    ciLow: metric.ciLow,
    ciHigh: metric.ciHigh,
    readable:
      metric.displayUnit !== null && periodLength
        ? formatTimeValued(metric.value, periodLength)
        : null,
  };
}

/** The descriptor for a code, or a generic one naming the code itself. */
export function describeMetric(code: MetricCode): MetricDescriptor {
  return (
    METRIC_DESCRIPTORS[code] ?? {
      label: code,
      family: MetricFamily.UNKNOWN,
      format: 'decimal',
      meaning:
        'A metric this build has no description for. Calculators are discovered at runtime, so this is a metric somebody added rather than an error.',
    }
  );
}

/**
 * One metric value, written the way its family reads.
 *
 * `periodLength` is required only for a `periods` metric and is what turns "14" into
 * "14 periods (14 days)". Without it - a comparison cell already converted server-side,
 * say - the period count is written plainly.
 */
export function formatMetricValue(
  value: number,
  format: MetricFormat,
  periodLength?: Duration,
): string {
  switch (format) {
    case 'ratio':
      return `${(value * 100).toFixed(1)}%`;
    case 'count':
      return String(Math.round(value));
    case 'hops':
      return `${value.toFixed(2)} hops`;
    case 'currency':
      return formatCurrency(value);
    case 'area':
      // Fill-rate × periods, and worth naming: a loss area of 3 means "performance halved for six
      // periods", and a bare 3 beside a cost of 3 invites the reader to think it is money. The unit
      // is normalised on purpose - an area between the served-demand curves would scale with the
      // size of the network and stop two configurations being comparable.
      return `${formatNumber(roundForDisplay(value))} fill·periods`;
    case 'periods':
      return periodLength
        ? formatTimeValued(value, periodLength)
        : `${formatNumber(value)} ${plural('period', value)}`;
    case 'decimal':
    default:
      return value.toFixed(4);
  }
}

/**
 * Money, grouped and to two decimals - `12 480.50`.
 *
 * No currency symbol. The model has no currency: costs are whatever unit the researcher entered
 * them in (carries `fixed_cost`, `var_cost` and `unit_value` as bare doubles), and printing
 * a `$` would invent one.
 */
export function formatCurrency(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
