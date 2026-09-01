import { MetricCode, TOPOLOGICAL_METRIC_CODES } from '../../core/models';
import {
  ALL_METRICS_HIDDEN,
  compareMetricCodes,
  metricOptions,
  metricRank,
  metricVisibilityControls,
} from './metric-visibility';

/**
 * Choosing which metrics the side-by-side panes print (FR-31).
 *
 * Three rules are worth a test and they are the three the module claims: the checkbox order **is**
 * the row order, the list comes from what the panes returned rather than from a table, and the two
 * bulk controls are each dead exactly when pressing them would change nothing. Nothing here
 * transcribes a sentence for its own sake - where a message is asserted, it is asserted against the
 * fact that produces it (`pane-grid.spec.ts` states that rule).
 */
describe('metric-visibility', () => {
  describe('metricRank', () => {
    it('ranks the structural suite in the registry’s own order', () => {
      expect(metricRank(MetricCode.DENSITY)).toBe(0);
      expect(metricRank(MetricCode.SPOF_NODE_COUNT)).toBeLessThan(
        metricRank(MetricCode.AVG_PATH),
      );
      expect(metricRank(MetricCode.ROBUSTNESS_TARGETED)).toBe(
        TOPOLOGICAL_METRIC_CODES.length - 1,
      );
    });

    it('sorts a code this build has never heard of last rather than dropping it', () => {
      expect(metricRank('SOME_NEW_CALCULATOR')).toBe(TOPOLOGICAL_METRIC_CODES.length);
    });
  });

  describe('compareMetricCodes', () => {
    it('is the order both the checkboxes and the panes’ rows are sorted by', () => {
      expect(compareMetricCodes(MetricCode.DENSITY, MetricCode.CLUSTERING)).toBeLessThan(0);
      expect(compareMetricCodes(MetricCode.CLUSTERING, MetricCode.DENSITY)).toBeGreaterThan(0);
      expect(compareMetricCodes(MetricCode.DENSITY, MetricCode.DENSITY)).toBe(0);
    });

    it('breaks a tie between two unrankable codes by name, so “last” is still an order', () => {
      // Both rank last. Without this the boxes would sort them by name and the rows by whatever
      // order the response arrived in - the one case where the n-th box stops governing the n-th
      // row, and the reason `SideBySideStore.networkScoped` calls this rather than the rank alone.
      expect(compareMetricCodes('ALPHA_METRIC', 'ZULU_METRIC')).toBeLessThan(0);
      expect(compareMetricCodes('ZULU_METRIC', 'ALPHA_METRIC')).toBeGreaterThan(0);
    });
  });

  describe('metricOptions', () => {
    it('offers one box per metric, in the order the panes print the rows', () => {
      // Deliberately shuffled: the response order is not the reading order, which is the whole
      // reason the rank is a shared function rather than "whatever arrived first".
      const options = metricOptions([
        MetricCode.ROBUSTNESS_RANDOM,
        MetricCode.DENSITY,
        MetricCode.AVG_PATH,
      ]);
      expect(options.map((option) => option.code)).toEqual([
        MetricCode.DENSITY,
        MetricCode.AVG_PATH,
        MetricCode.ROBUSTNESS_RANDOM,
      ]);
    });

    it('agrees with metricRank for every pair, so a box never governs another box’s row', () => {
      const options = metricOptions([...TOPOLOGICAL_METRIC_CODES]);
      for (let at = 1; at < options.length; at += 1) {
        expect(metricRank(options[at - 1].code)).toBeLessThanOrEqual(
          metricRank(options[at].code),
        );
      }
    });

    it('folds twelve panes’ twelve copies of one suite into one choice', () => {
      const twelvePanes = Array.from({ length: 12 }, () => [
        MetricCode.DENSITY,
        MetricCode.CLUSTERING,
      ]).flat();
      expect(metricOptions(twelvePanes).map((option) => option.code)).toEqual([
        MetricCode.DENSITY,
        MetricCode.CLUSTERING,
      ]);
    });

    it('offers a box for a code this build cannot name, labelled by the code itself', () => {
      const options = metricOptions([MetricCode.DENSITY, 'SOME_NEW_CALCULATOR']);
      expect(options.map((option) => option.code)).toEqual([
        MetricCode.DENSITY,
        'SOME_NEW_CALCULATOR',
      ]);
      expect(options[1].label).toBe('SOME_NEW_CALCULATOR');
    });

    it('orders two unrankable codes by code, the only stable order there is for them', () => {
      const options = metricOptions(['ZULU_METRIC', 'ALPHA_METRIC']);
      expect(options.map((option) => option.code)).toEqual(['ALPHA_METRIC', 'ZULU_METRIC']);
    });

    it('offers nothing where the panes have returned nothing', () => {
      expect(metricOptions([])).toEqual([]);
    });

    it('carries each metric’s meaning, so the choice is an informed one', () => {
      const [density] = metricOptions([MetricCode.DENSITY]);
      expect(density.label).toBe('Density');
      expect(density.meaning.length).toBeGreaterThan(0);
    });
  });

  describe('metricVisibilityControls', () => {
    it('counts what is shown rather than leaving the reader to tally ticks', () => {
      expect(metricVisibilityControls(9, 5).summary).toBe('Showing 5 of 9 metrics.');
      expect(metricVisibilityControls(9, 9).summary).toBe('Showing all 9 metrics.');
      expect(metricVisibilityControls(9, 0).summary).toBe('Showing none of the 9 metrics.');
    });

    it('words the one-metric and the not-yet cases rather than printing “1 metrics”', () => {
      expect(metricVisibilityControls(1, 1).summary).toBe('Showing the one metric.');
      expect(metricVisibilityControls(0, 0).summary).toContain('still reading');
    });

    it('disables each control exactly where pressing it would change nothing', () => {
      const everything = metricVisibilityControls(9, 9);
      expect(everything.selectAll.disabled).toBeTrue();
      expect(everything.selectNone.disabled).toBeFalse();

      const nothing = metricVisibilityControls(9, 0);
      expect(nothing.selectAll.disabled).toBeFalse();
      expect(nothing.selectNone.disabled).toBeTrue();
    });

    it('leaves both live in the mixed state, which is a filter’s working state (FR-31)', () => {
      // The whole reason there are two controls and not one: from five of nine, both destinations
      // are one press away. `collapseAllControl` resolves its mixed case in one direction because a
      // window is normally uniform; a filter in use never is.
      const mixed = metricVisibilityControls(9, 5);
      expect(mixed.selectAll.disabled).toBeFalse();
      expect(mixed.selectNone.disabled).toBeFalse();
    });

    it('disables both where there is nothing to choose from yet', () => {
      const empty = metricVisibilityControls(0, 0);
      expect(empty.selectAll.disabled).toBeTrue();
      expect(empty.selectNone.disabled).toBeTrue();
    });

    it('says why in the hint, in both states', () => {
      expect(metricVisibilityControls(9, 9).selectAll.hint).toContain('already shown');
      expect(metricVisibilityControls(9, 0).selectNone.hint).toContain('already hidden');
      // And the live hint promises what the store actually does: nothing is fetched either way.
      expect(metricVisibilityControls(9, 5).selectAll.hint).toContain('Nothing is re-read');
    });

    it('takes a nonsense count as a count of nothing, like the grid rule beside it', () => {
      expect(metricVisibilityControls(Number.NaN, 3).summary).toContain('still reading');
      // More shown than offered is not describable, so it is clamped rather than printed.
      expect(metricVisibilityControls(4, 9).summary).toBe('Showing all 4 metrics.');
    });
  });

  describe('ALL_METRICS_HIDDEN', () => {
    it('blames the filter rather than the data, and names the remedy', () => {
      // The pane's other empty sentence is a fact about the network; this one is a consequence of a
      // control the reader is holding, and the control is above the grid rather than in the pane.
      expect(ALL_METRICS_HIDDEN).toContain('No metrics are selected');
      expect(ALL_METRICS_HIDDEN).toContain('Select all');
    });
  });
});
