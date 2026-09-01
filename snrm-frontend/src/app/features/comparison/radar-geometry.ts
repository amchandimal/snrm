import { ComparisonRow, MetricDirection } from '../../core/models';

/**
 * The radar chart of normalised metrics.
 *
 * > "radar chart of normalised metrics"
 *
 * Free of Angular and of the DOM, for the same reason as `curve-geometry.ts`: the normalisation is
 * an interpretation of the data, and an interpretation should be testable.
 *
 * ## What "normalised" has to mean here, and why
 *
 * The suite mixes a fill rate in [0,1], a cost in thousands, a recovery time in periods and a count
 * of single points of failure. Plotting them on one radial axis needs every value mapped into a
 * common [0,1], and three decisions fall out of that:
 *
 * **Normalised against the compared set, not against an absolute scale.** There is no absolute scale
 * for a cost. Each axis is min–max scaled across the variants in *this* comparison, so the chart
 * answers "which of these is better on this metric" and nothing else. Adding a variant redraws every
 * axis - which is honest, and is why the axis labels carry the actual values on hover rather than
 * the normalised ones.
 *
 * **Oriented so that outward is always better.** A `LOWER_IS_BETTER` axis is inverted during
 * normalisation. Without it the reader has to remember, per axis, which direction is good, and the
 * one thing a radar chart is for - "is this shape bigger than that shape" - stops working.
 *
 * **`NEUTRAL` metrics are excluded.** A density has no better direction (see `MetricDirection`), so
 * it cannot be oriented, so plotting it would put an axis on the chart whose outward direction means
 * nothing. Excluding it is the only reading that does not invent a preference the metric does not have.
 *
 * ## Degenerate axes
 *
 * When every variant scores the same on a metric, min–max scaling is 0/0. Such an axis is drawn at
 * full radius for everyone: the variants are tied, and tying at 1 rather than at 0 keeps a metric
 * nobody differs on from denting every shape identically and looking like a shared weakness.
 */

/** One axis of the radar: a metric, oriented so that outward is better. */
export interface RadarAxis {
  readonly metricCode: string;
  readonly label: string;
  /** True when every variant scored the same and the axis carries no information. */
  readonly tied: boolean;
}

/** One variant's shape: a value in [0,1] per axis, aligned with {@link RadarChart.axes}. */
export interface RadarSeries {
  readonly networkId: number;
  readonly label: string;
  readonly values: readonly number[];
  /** The raw values behind them, for the tooltip. Null where the variant had no value. */
  readonly raw: readonly (number | null)[];
}

export interface RadarChart {
  readonly axes: readonly RadarAxis[];
  readonly series: readonly RadarSeries[];
}

/**
 * Builds the chart from the matrix the server returned.
 *
 * A variant with no value on an axis is plotted at 0 on it, and the tooltip says "no value" - the
 * alternative is a broken polygon, and a gap in a closed shape reads as a score of zero anyway.
 * Only variants with at least one value get a series at all, so a column that has never been
 * simulated does not draw a point at the centre and look like the worst configuration in the study.
 */
export function buildRadar(
  rows: readonly ComparisonRow[],
  variants: readonly { networkId: number; name: string; version: number }[],
  label: (metricCode: string) => string,
): RadarChart {
  const ranked = rows.filter((row) => row.direction !== MetricDirection.NEUTRAL);

  const axes: RadarAxis[] = [];
  const columns: number[][] = [];
  const rawColumns: (number | null)[][] = [];

  for (const row of ranked) {
    const values = row.cells.map((cell) => (cell ? cell.value : null));
    const present = values.filter((value): value is number => value !== null && Number.isFinite(value));
    if (present.length === 0) {
      // Nothing measured this metric in any column; an empty axis is not an axis.
      continue;
    }
    const min = Math.min(...present);
    const max = Math.max(...present);
    const span = max - min;
    const tied = span === 0;

    axes.push({ metricCode: row.metricCode, label: label(row.metricCode), tied });
    columns.push(
      values.map((value) => {
        if (value === null || !Number.isFinite(value)) {
          return 0;
        }
        if (tied) {
          return 1;
        }
        const scaled = (value - min) / span;
        // Invert so outward is better on every axis, whatever the metric's direction.
        return row.direction === MetricDirection.LOWER_IS_BETTER ? 1 - scaled : scaled;
      }),
    );
    rawColumns.push(values);
  }

  const series: RadarSeries[] = [];
  variants.forEach((variant, index) => {
    const raw = rawColumns.map((column) => column[index] ?? null);
    if (raw.every((value) => value === null)) {
      return;
    }
    series.push({
      networkId: variant.networkId,
      label: `${variant.name} v${variant.version}`,
      values: columns.map((column) => column[index] ?? 0),
      raw,
    });
  });

  return { axes, series };
}

/** Where an axis points, in SVG coordinates. Starts at twelve o'clock and goes clockwise. */
export function axisAngle(index: number, count: number): number {
  return (index / Math.max(1, count)) * 2 * Math.PI - Math.PI / 2;
}

export interface RadarPoint {
  readonly x: number;
  readonly y: number;
}

/** A point at `radius × value` along axis `index`. */
export function radarPoint(
  index: number,
  count: number,
  value: number,
  centre: RadarPoint,
  radius: number,
): RadarPoint {
  const angle = axisAngle(index, count);
  const clamped = Math.max(0, Math.min(1, value));
  return {
    x: centre.x + Math.cos(angle) * radius * clamped,
    y: centre.y + Math.sin(angle) * radius * clamped,
  };
}

/** One series as a closed SVG polygon's `points` attribute. */
export function seriesPoints(
  series: RadarSeries,
  centre: RadarPoint,
  radius: number,
): string {
  return series.values
    .map((value, index) => {
      const point = radarPoint(index, series.values.length, value, centre, radius);
      return `${round(point.x)},${round(point.y)}`;
    })
    .join(' ');
}

/** The web's rings, as polygons, so the grid matches the shapes drawn on it. */
export function ringPoints(
  axisCount: number,
  fraction: number,
  centre: RadarPoint,
  radius: number,
): string {
  const points: string[] = [];
  for (let index = 0; index < axisCount; index++) {
    const point = radarPoint(index, axisCount, fraction, centre, radius);
    points.push(`${round(point.x)},${round(point.y)}`);
  }
  return points.join(' ');
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
