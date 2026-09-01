import { buildRadar } from './radar-geometry';
import { ComparisonCell, ComparisonRow, MetricDirection } from '../../core/models';

function cell(value: number): ComparisonCell {
  return { value, ciLow: null, ciHigh: null, periods: null, best: false };
}

function row(
  metricCode: string,
  direction: MetricDirection,
  values: readonly (number | null)[],
): ComparisonRow {
  return {
    metricCode,
    direction,
    timeValued: false,
    unit: null,
    cells: values.map((value) => (value === null ? null : cell(value))),
  };
}

const variants = [
  { networkId: 1, name: 'Baseline', version: 1 },
  { networkId: 2, name: 'Baseline', version: 2 },
];

const label = (code: string) => code;

describe('radar-geometry', () => {
  describe('buildRadar', () => {
    it('orients every axis so that outward is better', () => {
      const chart = buildRadar(
        [
          // Higher is better: 0.9 beats 0.5, so variant 1 sits further out.
          row('FILL_RATE', MetricDirection.HIGHER_IS_BETTER, [0.5, 0.9]),
          // Lower is better: 10 beats 20, so variant 0 sits further out on this one.
          row('TTR', MetricDirection.LOWER_IS_BETTER, [10, 20]),
        ],
        variants,
        label,
      );

      expect(chart.axes.map((axis) => axis.metricCode)).toEqual(['FILL_RATE', 'TTR']);
      expect(chart.series[0].values).toEqual([0, 1]);
      expect(chart.series[1].values).toEqual([1, 0]);
    });

    it('excludes NEUTRAL metrics, which have no outward direction', () => {
      // A density is not better for being higher (see MetricDirection), so an axis for it would
      // point somewhere that means nothing.
      const chart = buildRadar(
        [
          row('DENSITY', MetricDirection.NEUTRAL, [0.3, 0.6]),
          row('FILL_RATE', MetricDirection.HIGHER_IS_BETTER, [0.5, 0.9]),
        ],
        variants,
        label,
      );
      expect(chart.axes.map((axis) => axis.metricCode)).toEqual(['FILL_RATE']);
    });

    it('draws a tied axis at full radius rather than dividing by zero', () => {
      const chart = buildRadar(
        [row('FILL_RATE', MetricDirection.HIGHER_IS_BETTER, [0.8, 0.8])],
        variants,
        label,
      );
      expect(chart.axes[0].tied).toBeTrue();
      expect(chart.series[0].values).toEqual([1]);
      expect(chart.series[1].values).toEqual([1]);
    });

    it('drops an axis no variant has a value for', () => {
      const chart = buildRadar(
        [row('TTR', MetricDirection.LOWER_IS_BETTER, [null, null])],
        variants,
        label,
      );
      expect(chart.axes).toEqual([]);
      expect(chart.series).toEqual([]);
    });

    it('omits a variant with no values at all rather than drawing it at the centre', () => {
      // A configuration that has never been simulated would otherwise plot as a point at the middle
      // and read as the worst in the study.
      const chart = buildRadar(
        [row('FILL_RATE', MetricDirection.HIGHER_IS_BETTER, [0.8, null])],
        variants,
        label,
      );
      expect(chart.series.length).toBe(1);
      expect(chart.series[0].networkId).toBe(1);
    });

    it('plots a missing value at 0 on an axis the variant does have others on', () => {
      const chart = buildRadar(
        [
          row('FILL_RATE', MetricDirection.HIGHER_IS_BETTER, [0.5, 0.9]),
          row('TTR', MetricDirection.LOWER_IS_BETTER, [10, null]),
        ],
        variants,
        label,
      );
      const second = chart.series.find((series) => series.networkId === 2)!;
      expect(second.values[1]).toBe(0);
      expect(second.raw[1]).toBeNull();
    });

    it('normalises against the compared set, so adding a variant redraws the axes', () => {
      const two = buildRadar(
        [row('FILL_RATE', MetricDirection.HIGHER_IS_BETTER, [0.5, 0.9])],
        variants,
        label,
      );
      const three = buildRadar(
        [row('FILL_RATE', MetricDirection.HIGHER_IS_BETTER, [0.5, 0.9, 1.0])],
        [...variants, { networkId: 3, name: 'Baseline', version: 3 }],
        label,
      );
      expect(two.series[1].values[0]).toBe(1);
      expect(three.series[1].values[0]).toBeCloseTo(0.8, 10);
    });
  });
});
