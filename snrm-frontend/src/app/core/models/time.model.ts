import { Id } from './common.model';

/**
 * The unit-bearing wire types / FR-13, and the resolution report.
 *
 * Every duration and every rate in the API is a `{value, unit}` object rather than a bare number,
 * because a bare number means "two of whatever the reader assumed" and two networks with different
 * period lengths then disagree about what the same payload says. The frontend keeps the pair intact
 * end to end: it displays what the user entered and never restates a stored figure into periods
 * behind their back.
 *
 * Mirrors `DurationDto`, `RateDto`, `TimeUnit`, `RoundingPolicy` and `TimeValidationReport` on the
 * backend. Conversion arithmetic lives in `core/time-units.ts`, deliberately apart from the shapes.
 */

/** The unit a duration or a rate is expressed in. */
export const TimeUnit = {
  SECOND: 'SECOND',
  MINUTE: 'MINUTE',
  HOUR: 'HOUR',
  DAY: 'DAY',
  WEEK: 'WEEK',
  /** Fixed at 30 days - not calendar arithmetic. */
  MONTH: 'MONTH',
  /** Fixed at 365 days - not calendar arithmetic. */
  YEAR: 'YEAR',
} as const;

export type TimeUnit = (typeof TimeUnit)[keyof typeof TimeUnit];

/** Every unit, finest first - the order the dropdowns list them in. */
export const TIME_UNITS: readonly TimeUnit[] = [
  TimeUnit.SECOND,
  TimeUnit.MINUTE,
  TimeUnit.HOUR,
  TimeUnit.DAY,
  TimeUnit.WEEK,
  TimeUnit.MONTH,
  TimeUnit.YEAR,
];

/**
 * Seconds in one of each unit - the common denominator every conversion goes through.
 *
 * `MONTH` is always 30 days and `YEAR` always 365, matching `TimeUnit.secondsOf()` on the backend.
 * These are fixed lengths, not calendar arithmetic: a duration must convert to the same number of
 * seconds whatever date a run starts on, or identical inputs stop producing identical outputs.
 * The UI says so wherever a unit is picked.
 */
export const SECONDS_OF: Readonly<Record<TimeUnit, number>> = {
  SECOND: 1,
  MINUTE: 60,
  HOUR: 3_600,
  DAY: 86_400,
  WEEK: 604_800,
  MONTH: 2_592_000,
  YEAR: 31_536_000,
};

/** Compact form for canvas labels and tooltips - "6 h", "2 wk". */
export const TIME_UNIT_ABBREVIATION: Readonly<Record<TimeUnit, string>> = {
  SECOND: 's',
  MINUTE: 'min',
  HOUR: 'h',
  DAY: 'd',
  WEEK: 'wk',
  MONTH: 'mo',
  YEAR: 'y',
};

/** Long form for dropdown options and prose. */
export const TIME_UNIT_LABEL: Readonly<Record<TimeUnit, string>> = {
  SECOND: 'seconds',
  MINUTE: 'minutes',
  HOUR: 'hours',
  DAY: 'days',
  WEEK: 'weeks',
  MONTH: 'months (30 d)',
  YEAR: 'years (365 d)',
};

/**
 * A length of time: the number and the unit it is counted in.
 *
 * Both parts are always present on the wire. Omitting the *whole* object in a request means the
 * field's empty form in the network's period unit, which is how a link created by a mouse gesture
 * can carry no lead time yet.
 */
export interface Duration {
  readonly value: number;
  readonly unit: TimeUnit;
}

/**
 * A quantity per unit time: the number and the unit it is measured over.
 *
 * A null `value` means unconstrained, which is how an uncapped node or link is expressed. The unit
 * stays present even then, so the pair is always well-formed and giving the capacity a number later
 * does not need a unit invented for it.
 */
export interface Rate {
  readonly value: number | null;
  readonly timeUnit: TimeUnit;
}

/** How a duration that does not divide evenly into the period is resolved. */
export const RoundingPolicy = {
  /** Half-up to the closest whole period. Unbiased over many values; the default. */
  NEAREST: 'NEAREST',
  /** Ceiling - any remainder costs a whole period. The conservative choice. */
  UP: 'UP',
  /** Floor - any remainder is discarded. The optimistic choice. */
  DOWN: 'DOWN',
} as const;

export type RoundingPolicy = (typeof RoundingPolicy)[keyof typeof RoundingPolicy];

export const ROUNDING_POLICIES: readonly RoundingPolicy[] = [
  RoundingPolicy.NEAREST,
  RoundingPolicy.UP,
  RoundingPolicy.DOWN,
];

/** One line of prose per policy, for the settings dialog - the choice is not neutral. */
export const ROUNDING_POLICY_HINT: Readonly<Record<RoundingPolicy, string>> = {
  NEAREST: 'Half-up to the closest whole period. Unbiased across many values.',
  UP: 'Any remainder costs a whole period. Overstates delays - the conservative choice.',
  DOWN: 'Any remainder is discarded. Understates delays, so it flatters the network.',
};

// ------------------------------------------------------------------ resolution validation

/** What kind of element a {@link TimeFinding} is about, so the banner can select it. */
export const TimeElementType = {
  NODE: 'NODE',
  LINK: 'LINK',
  DISRUPTION_EVENT: 'DISRUPTION_EVENT',
} as const;

export type TimeElementType = (typeof TimeElementType)[keyof typeof TimeElementType];

/** The four resolution checks. The code is contract; the message is prose. */
export const TimeCheck = {
  /** A positive duration that converts to 0 periods - it becomes instantaneous. */
  DURATION_ROUNDS_TO_ZERO: 'DURATION_ROUNDS_TO_ZERO',
  /** Rounded value differs from the declared one by more than 10%. */
  DURATION_ROUNDING_ERROR: 'DURATION_ROUNDING_ERROR',
  /** Period more than 1000× finer than the longest duration - a cost warning, not an error. */
  PERIOD_TOO_FINE: 'PERIOD_TOO_FINE',
  /** An event whose start offset plus duration lands after the horizon. */
  EVENT_EXCEEDS_HORIZON: 'EVENT_EXCEEDS_HORIZON',
} as const;

export type TimeCheck = (typeof TimeCheck)[keyof typeof TimeCheck] | (string & {});

/** Warning in the editor, error at import - the one thing context changes. */
export const TimeSeverity = {
  WARNING: 'WARNING',
  ERROR: 'ERROR',
} as const;

export type TimeSeverity = (typeof TimeSeverity)[keyof typeof TimeSeverity];

/** Which severity rule the report was produced under. */
export const TimeValidationContext = {
  EDITOR: 'EDITOR',
  IMPORT: 'IMPORT',
} as const;

export type TimeValidationContext =
  (typeof TimeValidationContext)[keyof typeof TimeValidationContext];

/**
 * One duration that converts badly onto the network's period.
 *
 * Null members are omitted from the JSON, so the two checks that are not about a single duration's
 * arithmetic - `PERIOD_TOO_FINE`, `EVENT_EXCEEDS_HORIZON` - simply leave the inapplicable parts out.
 */
export interface TimeFinding {
  readonly elementType: TimeElementType;
  /** Id of the offending element; the banner selects it on the canvas. */
  readonly elementId: Id;
  /** A node's name, a link's two endpoints. */
  readonly elementName: string;
  /** The attribute at fault: `leadTime`, `processingTime`, `window`. */
  readonly field: string;
  readonly code: TimeCheck;
  readonly severity: TimeSeverity;
  /** The duration as the user stated it. Absent where the check is not about one duration. */
  readonly declaredValue?: Duration;
  /** What that duration becomes on this network's clock, in whole periods. */
  readonly convertedPeriods?: number;
  /** Signed relative error, in percent: −100 means the duration vanished. */
  readonly errorPercent?: number;
  readonly message: string;
}

/**
 * `GET /networks/{id}/time-validation`, and the second half of `PUT /networks/{id}/time-base`.
 *
 * The clock is echoed back rather than left for the client to remember, so a report is
 * self-contained: "rounds to 2 periods" is only interpretable next to the period it was computed
 * against.
 */
export interface TimeValidationReport {
  readonly networkId: Id;
  /** The scenario whose events were checked, when one was named. */
  readonly scenarioId?: Id;
  readonly context: TimeValidationContext;
  readonly periodLength: Duration;
  readonly roundingPolicy: RoundingPolicy;
  readonly horizonPeriods: number;
  /**
   * The coarsest period keeping every declared duration within 10% - what the settings dialog's
   * "Suggest period" offers. Null when the network declares no positive duration.
   */
  readonly suggestedPeriod?: Duration;
  readonly errorCount: number;
  readonly warningCount: number;
  /** Every finding, worst severity first. */
  readonly findings: readonly TimeFinding[];
}
