import {
  describeMetric,
  formatInUnit,
  formatMetricValue,
  formatTimeValued,
  MetricFamily,
  periodAxisLabel,
  periodReadout,
  readablePeriods,
} from './metric-display';
import { TimeUnit } from './models/time.model';

/**
 * The reporting rule, pinned.
 *
 * Most of these assertions are about one multiplication. A metric's `displayUnit` names the unit a
 * period is *stated* in, not how long a period is, so restating a period count needs the period's
 * value as well as its unit - and on the 1-unit periods almost every test network uses, leaving the
 * value out gives the right answer anyway. That is exactly why it is worth a test.
 */
describe('metric-display', () => {
  describe('readablePeriods', () => {
    it('restates 14 periods of 1 day as 14 days', () => {
      expect(readablePeriods(14, { value: 1, unit: TimeUnit.DAY })).toEqual({
        value: 14,
        unit: TimeUnit.DAY,
      });
    });

    it('multiplies by the period value, not just its unit', () => {
      // The case a 1-day period hides: 14 periods of 2 days is 28 days, not 14.
      expect(readablePeriods(14, { value: 2, unit: TimeUnit.DAY })).toEqual({
        value: 28,
        unit: TimeUnit.DAY,
      });
    });

    it('keeps the period unit rather than promoting to a coarser one', () => {
      // 20 periods of 6 h is 120 h. Saying "5 days" would be true and would restate a figure in a
      // unit the researcher never chose (the user's own phrasing is kept).
      expect(readablePeriods(20, { value: 6, unit: TimeUnit.HOUR })).toEqual({
        value: 120,
        unit: TimeUnit.HOUR,
      });
    });

    it('handles a fractional period count - TTR is a mean across replications', () => {
      expect(readablePeriods(3.5, { value: 1, unit: TimeUnit.DAY })).toEqual({
        value: 3.5,
        unit: TimeUnit.DAY,
      });
    });

    it('trims floating-point noise from the multiplication', () => {
      const readable = readablePeriods(3, { value: 0.1, unit: TimeUnit.HOUR });
      expect(readable.value).toBe(0.3);
    });
  });

  describe('formatTimeValued', () => {
    it('writes both halves', () => {
      expect(formatTimeValued(14, { value: 1, unit: TimeUnit.DAY })).toBe('14 periods (14 days)');
    });

    it('singularises both halves', () => {
      expect(formatTimeValued(1, { value: 1, unit: TimeUnit.DAY })).toBe('1 period (1 day)');
    });

    it('shows the converted figure when the period is not one unit long', () => {
      expect(formatTimeValued(14, { value: 2, unit: TimeUnit.DAY })).toBe('14 periods (28 days)');
    });
  });

  describe('periodAxisLabel', () => {
    it('names the period on the axis, since a period index alone means nothing', () => {
      expect(periodAxisLabel({ value: 1, unit: TimeUnit.DAY })).toBe('Period (1 day)');
      expect(periodAxisLabel({ value: 6, unit: TimeUnit.HOUR })).toBe('Period (6 hours)');
    });
  });

  describe('periodReadout (FR-18, FR-22)', () => {
    it('writes the canonical readout', () => {
      // The editor's playback transport and the results dashboard's period cursor both print this
      // string for the same period of the same run; one researcher must not be shown two phrasings.
      expect(periodReadout(14, 52, { value: 1, unit: TimeUnit.DAY })).toBe(
        'Period 14 of 52 - 14 days',
      );
    });

    it('restates through the period’s value, not only its unit', () => {
      // The slip every 1-unit-period test network hides: 14 periods of 2 days is 28 days.
      expect(periodReadout(14, 52, { value: 2, unit: TimeUnit.DAY })).toBe(
        'Period 14 of 52 - 28 days',
      );
    });

    it('counts the horizon and indexes the period - period 51 of 52 is the last one', () => {
      expect(periodReadout(51, 52, { value: 1, unit: TimeUnit.DAY })).toBe(
        'Period 51 of 52 - 51 days',
      );
      expect(periodReadout(0, 52, { value: 1, unit: TimeUnit.DAY })).toBe('Period 0 of 52 - 0 days');
    });

    it('prints the index alone where the run has no clock recorded', () => {
      // Rather than inventing a duration for it - the rule every absence in this application follows.
      expect(periodReadout(14, 52, null)).toBe('Period 14 of 52');
    });
  });

  describe('formatMetricValue', () => {
    it('writes a fill rate as a percentage', () => {
      expect(formatMetricValue(0.8234, 'ratio')).toBe('82.3%');
    });

    it('writes a structural ratio as a decimal - a density is not a percentage', () => {
      expect(formatMetricValue(0.4667, 'decimal')).toBe('0.4667');
    });

    it('rounds a count', () => {
      expect(formatMetricValue(3, 'count')).toBe('3');
    });

    it('names the units of a loss area rather than leaving the number bare', () => {
      // fill-rate × periods, not money and not demand: LOSS_AREA is normalised so two networks of
      // different sizes are comparable.
      expect(formatMetricValue(3, 'area')).toBe('3 fill·periods');
    });

    it('reads a period count with the clock when it has one', () => {
      expect(formatMetricValue(14, 'periods', { value: 1, unit: TimeUnit.DAY })).toBe(
        '14 periods (14 days)',
      );
    });

    it('falls back to the bare period count without a clock', () => {
      expect(formatMetricValue(14, 'periods')).toBe('14 periods');
    });
  });

  describe('formatInUnit', () => {
    it('writes a converted comparison value in the row unit', () => {
      expect(formatInUnit(336, TimeUnit.HOUR)).toBe('336 hours');
    });
  });

  describe('describeMetric', () => {
    it('describes a known code with its metric family', () => {
      expect(describeMetric('TTR').family).toBe(MetricFamily.RECOVERY);
      expect(describeMetric('TOTAL_COST').family).toBe(MetricFamily.ECONOMIC);
    });

    it('renders an unknown code rather than rejecting it', () => {
      const descriptor = describeMetric('TIME_TO_SURVIVE');
      expect(descriptor.label).toBe('TIME_TO_SURVIVE');
      expect(descriptor.family).toBe(MetricFamily.UNKNOWN);
    });
  });
});
