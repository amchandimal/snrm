import { Duration, Rate } from './models';
import {
  convertDuration,
  convertRate,
  durationInPeriods,
  durationSeconds,
  formatDuration,
  formatRate,
  formatSpan,
  periodsForSpan,
  ratePerPeriod,
  sameFieldValue,
} from './time-units';

/**
 * The arithmetic, checked against `docs/time-units-worked-example.md` in the backend
 * repository - every expectation below is a row of that document, so a drift between the two ends
 * of the wire fails here rather than in a simulation result.
 */

const ONE_DAY: Duration = { value: 1, unit: 'DAY' };

describe('durationSeconds', () => {
  it('reduces every unit to the canonical seconds', () => {
    expect(durationSeconds({ value: 6, unit: 'HOUR' })).toBe(21_600);
    expect(durationSeconds({ value: 36, unit: 'HOUR' })).toBe(129_600);
    expect(durationSeconds({ value: 2, unit: 'WEEK' })).toBe(1_209_600);
  });

  it('treats a month as 30 days and a year as 365, not as calendar arithmetic', () => {
    expect(durationSeconds({ value: 1, unit: 'MONTH' })).toBe(2_592_000);
    expect(durationSeconds({ value: 1, unit: 'YEAR' })).toBe(31_536_000);
  });
});

describe('convertDuration', () => {
  it('restates the same length of time rather than reinterpreting the number', () => {
    // The rule the unit dropdown follows: 36 HOUR becomes 1.5 DAY, never 36 DAY.
    expect(convertDuration({ value: 36, unit: 'HOUR' }, 'DAY')).toEqual({ value: 1.5, unit: 'DAY' });
    expect(convertDuration({ value: 2, unit: 'WEEK' }, 'DAY')).toEqual({ value: 14, unit: 'DAY' });
    expect(convertDuration({ value: 0.5, unit: 'DAY' }, 'HOUR')).toEqual({ value: 12, unit: 'HOUR' });
  });

  it('keeps a repeating decimal exact to display precision', () => {
    // 1 week is 0.2333… months. The restatement is not promised to round-trip bit-exactly - undo
    // is what restores the pair verbatim - but it must not drift meaningfully.
    const oneWeekInMonths = convertDuration({ value: 1, unit: 'WEEK' }, 'MONTH');
    expect(convertDuration(oneWeekInMonths, 'WEEK').value).toBeCloseTo(1, 9);
  });

  it('returns the same object when the unit is unchanged', () => {
    const duration: Duration = { value: 3, unit: 'DAY' };
    expect(convertDuration(duration, 'DAY')).toBe(duration);
  });
});

describe('durationInPeriods', () => {
  it('matches the worked example at a one-day period under NEAREST', () => {
    expect(durationInPeriods({ value: 12, unit: 'HOUR' }, ONE_DAY, 'NEAREST')).toBe(1);
    expect(durationInPeriods({ value: 4, unit: 'HOUR' }, ONE_DAY, 'NEAREST')).toBe(0);
    expect(durationInPeriods({ value: 6, unit: 'HOUR' }, ONE_DAY, 'NEAREST')).toBe(0);
    expect(durationInPeriods({ value: 36, unit: 'HOUR' }, ONE_DAY, 'NEAREST')).toBe(2);
    expect(durationInPeriods({ value: 2, unit: 'WEEK' }, ONE_DAY, 'NEAREST')).toBe(14);
  });

  it('rounds the same remainder in opposite directions under UP and DOWN', () => {
    const twelveHours: Duration = { value: 12, unit: 'HOUR' };
    expect(durationInPeriods(twelveHours, ONE_DAY, 'UP')).toBe(1);
    expect(durationInPeriods(twelveHours, ONE_DAY, 'DOWN')).toBe(0);
  });
});

describe('convertRate', () => {
  it('scales inversely to a duration - a shorter denominator is a smaller number', () => {
    expect(convertRate({ value: 500, timeUnit: 'DAY' }, 'HOUR').value).toBeCloseTo(20.8333333, 6);
    expect(convertRate({ value: 400, timeUnit: 'WEEK' }, 'DAY')).toEqual({
      value: 57.1428571429,
      timeUnit: 'DAY',
    });
  });

  it('moves the unit of an unconstrained rate but leaves it unconstrained', () => {
    expect(convertRate({ value: null, timeUnit: 'DAY' }, 'HOUR')).toEqual({
      value: null,
      timeUnit: 'HOUR',
    });
  });
});

describe('ratePerPeriod', () => {
  it('matches the worked example at a one-day period', () => {
    expect(ratePerPeriod({ value: 500, timeUnit: 'DAY' }, ONE_DAY)).toBe(500);
    expect(ratePerPeriod({ value: 400, timeUnit: 'WEEK' }, ONE_DAY)).toBeCloseTo(57.142857, 6);
    expect(ratePerPeriod({ value: 120, timeUnit: 'WEEK' }, ONE_DAY)).toBeCloseTo(17.142857, 6);
  });

  it('leaves an unconstrained capacity unconstrained', () => {
    expect(ratePerPeriod({ value: null, timeUnit: 'DAY' }, ONE_DAY)).toBeNull();
  });
});

describe('formatting', () => {
  it('writes a duration in its own unit, never in periods', () => {
    expect(formatDuration({ value: 6, unit: 'HOUR' })).toBe('6 h');
    expect(formatDuration({ value: 2, unit: 'WEEK' })).toBe('2 wk');
    expect(formatDuration({ value: 1.5, unit: 'DAY' })).toBe('1.5 d');
    expect(formatDuration({ value: 0, unit: 'DAY' })).toBe('0 d');
  });

  it('writes a rate over its own unit and names the unconstrained case', () => {
    expect(formatRate({ value: 400, timeUnit: 'WEEK' })).toBe('400 / wk');
    expect(formatRate({ value: null, timeUnit: 'DAY' })).toBe('unconstrained');
  });

  it('describes a span in at most two units', () => {
    expect(formatSpan(52 * 86_400)).toBe('52 d');
    expect(formatSpan(52 * 7_200)).toBe('4 d 8 h');
  });
});

describe('periodsForSpan', () => {
  it('answers the worked example: 52 days at a two-hour period needs 624 periods', () => {
    expect(periodsForSpan(52 * 86_400, { value: 2, unit: 'HOUR' })).toBe(624);
  });

  it('never proposes a horizon below one period', () => {
    expect(periodsForSpan(0, ONE_DAY)).toBe(1);
  });
});

describe('sameFieldValue', () => {
  it('compares durations and rates by value, so an unchanged field sends no PATCH', () => {
    expect(sameFieldValue({ value: 6, unit: 'HOUR' }, { value: 6, unit: 'HOUR' })).toBeTrue();
    expect(sameFieldValue({ value: 6, unit: 'HOUR' }, { value: 6, unit: 'DAY' })).toBeFalse();
  });

  it('treats 24 HOUR and 1 DAY as different statements of the same length', () => {
    // The pair is kept because the user's phrasing is data: re-unitising is a real edit.
    expect(sameFieldValue({ value: 24, unit: 'HOUR' }, { value: 1, unit: 'DAY' })).toBeFalse();
  });

  it('distinguishes an unconstrained rate from a zero one', () => {
    const unconstrained: Rate = { value: null, timeUnit: 'DAY' };
    const zero: Rate = { value: 0, timeUnit: 'DAY' };
    expect(sameFieldValue(unconstrained, zero)).toBeFalse();
    expect(sameFieldValue(unconstrained, { value: null, timeUnit: 'DAY' })).toBeTrue();
  });

  it('falls back to identity for plain values', () => {
    expect(sameFieldValue(3, 3)).toBeTrue();
    expect(sameFieldValue('EU-West', 'EU-West')).toBeTrue();
    expect(sameFieldValue(null, 4)).toBeFalse();
  });
});
