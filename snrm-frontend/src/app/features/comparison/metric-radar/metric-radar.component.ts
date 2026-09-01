import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { describeMetric } from '../../../core/metric-display';
import { ComparisonRow, ComparisonVariant } from '../../../core/models';
import {
  RadarPoint,
  axisAngle,
  buildRadar,
  radarPoint,
  ringPoints,
  seriesPoints,
} from '../radar-geometry';

/** The palette a variant's shape is drawn in, cycled by column index. */
const SERIES_COLOURS = ['#0d6efd', '#d63384', '#198754', '#fd7e14', '#6f42c1', '#20a4a4'];

/**
 * The radar chart of normalised metrics.
 *
 * Hand-drawn SVG, like the performance curve, and for the same reasons: the chart is small, the
 * geometry lives in `radar-geometry.ts` where it can be tested, and the bundle pays nothing.
 *
 * **Every axis points outward-is-better**, `NEUTRAL` metrics are excluded, and each axis is scaled
 * across the compared variants rather than against an absolute range. All three are decisions about
 * what the picture claims, and all three are argued in `radar-geometry.ts` - read that before
 * changing this.
 *
 * **The chart is not the finding; the matrix is.** A radar exaggerates differences on axes that
 * happen to have narrow ranges - min–max scaling makes a 1% spread look like the whole radius - so
 * this is deliberately placed beside the matrix rather than above it, and the axis tooltips carry the
 * real values.
 */
@Component({
  selector: 'app-metric-radar',
  standalone: true,
  templateUrl: './metric-radar.component.html',
  styleUrl: './metric-radar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricRadarComponent {
  readonly rows = input.required<readonly ComparisonRow[]>();
  readonly variants = input.required<readonly ComparisonVariant[]>();

  readonly size = 360;
  readonly radius = 118;
  readonly centre: RadarPoint = { x: 180, y: 168 };
  readonly rings = [0.25, 0.5, 0.75, 1];

  readonly chart = computed(() =>
    buildRadar(this.rows(), this.variants(), (code) => describeMetric(code).label),
  );

  readonly axisCount = computed(() => this.chart().axes.length);

  /**
   * Three axes is the fewest that makes a shape rather than a line, and below that the picture is
   * misleading rather than merely sparse.
   */
  readonly isDrawable = computed(() => this.axisCount() >= 3 && this.chart().series.length > 0);

  readonly ringPolygons = computed(() =>
    this.rings.map((fraction) => ringPoints(this.axisCount(), fraction, this.centre, this.radius)),
  );

  readonly spokes = computed(() =>
    this.chart().axes.map((axis, index) => {
      const end = radarPoint(index, this.axisCount(), 1, this.centre, this.radius);
      return { axis, x: end.x, y: end.y };
    }),
  );

  /** Axis captions, pushed just outside the web and anchored so they do not overrun the box. */
  readonly labels = computed(() =>
    this.chart().axes.map((axis, index) => {
      const point = radarPoint(index, this.axisCount(), 1, this.centre, this.radius + 16);
      const angle = axisAngle(index, this.axisCount());
      const cos = Math.cos(angle);
      return {
        axis,
        x: point.x,
        y: point.y,
        anchor: Math.abs(cos) < 0.25 ? 'middle' : cos > 0 ? 'start' : 'end',
      };
    }),
  );

  readonly polygons = computed(() =>
    this.chart().series.map((series, index) => ({
      series,
      points: seriesPoints(series, this.centre, this.radius),
      colour: SERIES_COLOURS[index % SERIES_COLOURS.length],
    })),
  );
}
