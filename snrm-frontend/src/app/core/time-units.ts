import {
  Duration,
  Rate,
  RoundingPolicy,
  SECONDS_OF,
  TIME_UNIT_ABBREVIATION,
  TimeUnit,
} from './models/time.model';

/**
 * Conversion and presentation for the unit-bearing values.
 *
 * Free of Angular and of HTTP on purpose: this is the arithmetic that has to be right - the same
 * formulas `TimeBasis` applies on the backend - and pure functions are the only version of it that
 * can be tested exhaustively. `time-units.spec.ts` pins every rule below.
 *
 * ```text
 * periodSeconds   = period.value × secondsOf(period.unit)
 * durationPeriods = round( durationSeconds / periodSeconds , policy )
 * ratePerPeriod   = rate.value × ( periodSeconds / secondsOf(rate.timeUnit) )
 * ```
 *
 * Nothing here rounds a declared value. Conversion for the engine happens once, server-side, when
 * the `NetworkGraph` snapshot is built; what these functions produce is either a
 * restatement of the same quantity in another unit (for the property panel) or a preview of what
 * the engine will do with it (for the warning banner).
 */

/**
 * Significant digits kept when a value is restated in another unit.
 *
 * A conversion to a finer unit can produce a repeating decimal - 500 per day is 20.83… per hour -
 * and the number box has to show *something*. Twelve digits keeps every figure a researcher types
 * exact and hides the noise of binary floating point (`20.833333333333332`), while staying short
 * enough to read in a narrow panel.
 *
 * It does **not** promise a bit-exact round trip: flipping DAY → HOUR → DAY on a repeating value
 * can land a unit in the last digit. Nothing is lost by it - a unit change is one undoable command,
 * so Ctrl-Z restores the pair the user actually entered, verbatim.
 */
const DISPLAY_PRECISION = 12;

/** Trims float noise without changing the quantity. See {@link DISPLAY_PRECISION}. */
export function roundForDisplay(value: number): number {
  if (!Number.isFinite(value) || value === 0) {
    return value;
  }
  return Number(value.toPrecision(DISPLAY_PRECISION));
}

// ------------------------------------------------------------------------- durations

/** A duration in seconds - the canonical form. */
export function durationSeconds(duration: Duration): number {
  return duration.value * SECONDS_OF[duration.unit];
}

/**
 * The same length of time, stated in another unit.
 *
 * **This restates; it never reinterprets.** 36 HOUR becomes 1.5 DAY, not 36 DAY - which is exactly
 * what the unit dropdown requires: changing a unit re-displays the value in that unit
 * rather than reinterpreting the number.
 */
export function convertDuration(duration: Duration, to: TimeUnit): Duration {
  if (duration.unit === to) {
    return duration;
  }
  return { value: roundForDisplay(durationSeconds(duration) / SECONDS_OF[to]), unit: to };
}

/** Length of one simulation period, in seconds. */
export function periodSeconds(periodLength: Duration): number {
  return durationSeconds(periodLength);
}

/** How many periods a duration is, before the rounding policy is applied. May be fractional. */
export function exactPeriods(duration: Duration, periodLength: Duration): number {
  const period = periodSeconds(periodLength);
  return period === 0 ? 0 : durationSeconds(duration) / period;
}

/** Applies a rounding policy to a real number of periods. */
export function applyRounding(periods: number, policy: RoundingPolicy): number {
  switch (policy) {
    case 'UP':
      return Math.ceil(periods);
    case 'DOWN':
      return Math.floor(periods);
    default:
      return Math.round(periods);
  }
}

/**
 * What the engine will use for this duration: whole periods under the network's policy.
 *
 * For the panel's restatement line only. The authoritative answer - including whether the
 * conversion is bad enough to warn about - comes from `GET /networks/{id}/time-validation`, which
 * runs the same arithmetic where the data lives.
 */
export function durationInPeriods(
  duration: Duration,
  periodLength: Duration,
  policy: RoundingPolicy,
): number {
  return applyRounding(exactPeriods(duration, periodLength), policy);
}

// ----------------------------------------------------------------------------- rates

/**
 * The same throughput, stated over another unit of time.
 *
 * The inverse direction of {@link convertDuration}: a *shorter* denominator means a *smaller*
 * number for the same flow - 500 per DAY is 20.83… per HOUR.
 */
export function convertRate(rate: Rate, to: TimeUnit): Rate {
  if (rate.timeUnit === to) {
    return rate;
  }
  if (rate.value === null) {
    // Unconstrained has no magnitude to restate, but the unit still moves: the pair stays
    // well-formed, so giving it a number later does not need a unit invented for it.
    return { value: null, timeUnit: to };
  }
  return {
    value: roundForDisplay((rate.value * SECONDS_OF[to]) / SECONDS_OF[rate.timeUnit]),
    timeUnit: to,
  };
}

/**
 * What the engine will use for this rate: the quantity per simulation period.
 *
 * Rates are rescaled, never rounded, so nothing is lost here - which is why the resolution checks
 * evaluate durations and not rates. The panel still shows the restatement, because
 * 120 per week is 17.14 per period and the second number is the one the engine runs on.
 */
export function ratePerPeriod(rate: Rate, periodLength: Duration): number | null {
  if (rate.value === null) {
    return null;
  }
  return roundForDisplay((rate.value * periodSeconds(periodLength)) / SECONDS_OF[rate.timeUnit]);
}

// ------------------------------------------------------------------------ presentation

/**
 * A number as a human would write it: `6`, not `6.0`; `1.5`, not `1.5000000001`.
 *
 * Four decimal places is enough for anything a researcher types and short enough for a canvas
 * label; very small values fall back to significant digits so a 0.0002-day dwell does not read as
 * zero.
 */
export function formatNumber(value: number): string {
  if (!Number.isFinite(value)) {
    return String(value);
  }
  if (Number.isInteger(value)) {
    return String(value);
  }
  if (Math.abs(value) >= 0.001) {
    return String(Number(value.toFixed(4)));
  }
  return String(Number(value.toPrecision(3)));
}

/**
 * A duration in its own unit - `6 h`, `2 wk`.
 *
 * Never in periods: the canvas label and the tooltip must read the way the network was entered,
 * because how many simulation steps that becomes is a property of the clock, not of the arc.
 */
export function formatDuration(duration: Duration): string {
  return `${formatNumber(duration.value)} ${TIME_UNIT_ABBREVIATION[duration.unit]}`;
}

/** A rate over its own unit - `400 / wk`, or `unconstrained` for a null value. */
export function formatRate(rate: Rate, unconstrainedLabel = 'unconstrained'): string {
  if (rate.value === null) {
    return unconstrainedLabel;
  }
  return `${formatNumber(rate.value)} / ${TIME_UNIT_ABBREVIATION[rate.timeUnit]}`;
}

/**
 * The ladder {@link formatSpan} counts down, coarsest first.
 *
 * It stops at the day on purpose. `WEEK` would make a 52-day horizon read "7 wk 3 d" where "52 d" is
 * plainer, and `MONTH`/`YEAR` are the fixed 30- and 365-day approximations - fine for
 * converting a declared duration the user chose to state that way, misleading as a summary the tool
 * invents. Capping at the day also means one wall-clock span always prints identically, whatever
 * period the network happens to step in, which is the comparison the dialog exists to support.
 */
const SPAN_LADDER: readonly TimeUnit[] = [
  TimeUnit.DAY,
  TimeUnit.HOUR,
  TimeUnit.MINUTE,
  TimeUnit.SECOND,
];

/**
 * A span of seconds as at most two units - `52 d`, `4 d 8 h`.
 *
 * For the time-settings dialog, which has to make "52 periods of 2 hours" mean something next to
 * "52 periods of 1 day", with the worked example's warning that the horizon does not follow
 * a period change automatically.
 */
export function formatSpan(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '0 s';
  }
  const major = SPAN_LADDER.find((unit) => seconds >= SECONDS_OF[unit]) ?? TimeUnit.SECOND;
  const majorCount = Math.floor(seconds / SECONDS_OF[major]);
  const remainder = seconds - majorCount * SECONDS_OF[major];
  const parts = [`${majorCount} ${TIME_UNIT_ABBREVIATION[major]}`];

  const minor = SPAN_LADDER.find(
    (unit) => SECONDS_OF[unit] < SECONDS_OF[major] && remainder >= SECONDS_OF[unit],
  );
  if (minor) {
    parts.push(`${Math.floor(remainder / SECONDS_OF[minor])} ${TIME_UNIT_ABBREVIATION[minor]}`);
  }
  return parts.join(' ');
}

/** Wall-clock length of a whole run: `horizonPeriods` periods, in seconds. */
export function horizonSeconds(periodLength: Duration, horizonPeriods: number): number {
  return periodSeconds(periodLength) * horizonPeriods;
}

/**
 * How many periods of `periodLength` cover `seconds` - the horizon a period change would need to
 * keep the run spanning the same wall-clock time.
 *
 * Rounded up, and never below 1: covering slightly more than the old span is a defensible default,
 * covering less silently shortens the study.
 */
export function periodsForSpan(seconds: number, periodLength: Duration): number {
  const period = periodSeconds(periodLength);
  if (period <= 0) {
    return 1;
  }
  return Math.max(1, Math.ceil(seconds / period));
}

// -------------------------------------------------------------------------- equality

/**
 * Value equality for a duration.
 *
 * Needed because the property panel decides whether a field changed before sending a PATCH, and
 * `{value: 6, unit: 'HOUR'} !== {value: 6, unit: 'HOUR'}` by reference. Deliberately **not**
 * seconds-equality: 24 HOUR and 1 DAY are the same length of time but not the same statement, and
 * the pair is kept precisely because the user's phrasing is data.
 */
export function sameDuration(a: Duration | null | undefined, b: Duration | null | undefined): boolean {
  if (!a || !b) {
    return a === b;
  }
  return a.value === b.value && a.unit === b.unit;
}

/** Value equality for a rate; see {@link sameDuration}. A null value is unconstrained, not absent. */
export function sameRate(a: Rate | null | undefined, b: Rate | null | undefined): boolean {
  if (!a || !b) {
    return a === b;
  }
  return a.value === b.value && a.timeUnit === b.timeUnit;
}

/**
 * Value equality for anything a property-panel field can hold.
 *
 * The store compares a proposed edit against each selected element to decide what actually changed;
 * with `Duration` and `Rate` in the mix that comparison can no longer be `===`.
 */
export function sameFieldValue(a: unknown, b: unknown): boolean {
  if (a === b) {
    return true;
  }
  if (isDuration(a) && isDuration(b)) {
    return sameDuration(a, b);
  }
  if (isRate(a) && isRate(b)) {
    return sameRate(a, b);
  }
  return false;
}

export function isDuration(value: unknown): value is Duration {
  return typeof value === 'object' && value !== null && 'unit' in value && 'value' in value;
}

export function isRate(value: unknown): value is Rate {
  return typeof value === 'object' && value !== null && 'timeUnit' in value && 'value' in value;
}
