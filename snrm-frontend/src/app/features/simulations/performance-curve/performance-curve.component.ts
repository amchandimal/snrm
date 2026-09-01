import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatUnits, periodAxisLabel } from '../../../core/metric-display';
import { Duration } from '../../../core/models';
import { ChartSize } from '../element-charts';
import {
  CurveBox,
  CurvePoint,
  FILL_TICKS,
  LossRegion,
  linePath,
  onsetPeriod,
  periodTicks,
  regionPath,
  scaleFor,
} from '../curve-geometry';

/**
 * The fill-rate-versus-period curve, with the baseline overlaid and the loss area shaded.
 *
 * > "fill-rate-vs-period curve with baseline overlay and shaded loss area (the resilience triangle
 * > rendered literally)"
 *
 * **Rendered literally** is the instruction, and it is why this is hand-drawn SVG rather than a
 * charting library's area series. The shape that has to be drawn is the region *between two curves*,
 * clipped to where one is below the other and closed at the crossings - which no stacked-area or
 * band primitive draws without being lied to about what the series are. Drawing it directly also
 * keeps the geometry in `curve-geometry.ts`, where it is testable against hand-worked numbers, and
 * costs the bundle nothing (no charting library is installed, and this screen turns out not to need
 * one).
 *
 * **The x-axis is labelled with the network's period unit**. A period index alone
 * is meaningless - the same 52-point curve is a year on one network and two days on another - so the
 * axis title carries the period length and every tick is a whole period, never an interpolated
 * fraction the simulation has no state for.
 *
 * **The y-axis is pinned to [0, 1].** Fitting it to the data would redraw the same disruption as a
 * cliff on one variant and a dip on another purely because their minima differ, which is exactly the
 * comparison the tool exists to make honestly.
 *
 * ## The shared period cursor is an optional input (FR-22)
 *
 * The results dashboard binds {@link cursorPeriod} to the one signal its element charts and its
 * miniature also read, so the curve carries the cursor's line at the same period they do: every
 * chart carries the cursor line. It defaults to **null**, which draws nothing, and the
 * editor's run panel renders this component with no such binding: a curve embedded beside a playback
 * transport must not sprout a second position marker that the transport does not drive.
 *
 * The line goes at `scale.x(period)`, which is where that period's own axis tick is. That is not the
 * band-centre rule the element charts use, and the difference is the two chart *kinds*: this is a
 * line through samples and labels the sample, a step chart draws a period as a band and labels the
 * middle of it. Each cursor falls through its own tick, which is what a reader checks it against.
 */
@Component({
  selector: 'app-performance-curve',
  standalone: true,
  templateUrl: './performance-curve.component.html',
  styleUrl: './performance-curve.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PerformanceCurveComponent {
  readonly points = input.required<readonly CurvePoint[]>();
  readonly regions = input.required<readonly LossRegion[]>();
  /** The evaluated network's clock, for the axis label. */
  readonly periodLength = input.required<Duration>();
  readonly horizonPeriods = input.required<number>();

  /**
   * The period the page's shared cursor stands on, or null where there is no cursor (FR-22).
   *
   * Unbound in the editor's run panel - see the class note.
   */
  readonly cursorPeriod = input<number | null>(null);

  /**
   * How large the curve is drawn.
   *
   * `large` is what this component has always drawn and stays the default, so the editor's run panel
   * renders exactly as it did. The results dashboard sets it: the curve is one of seven per-period
   * charts there, and any of them can be the enlarged one.
   */
  readonly size = input<ChartSize>('large');

  /**
   * The drawing box, in the SVG's own user units.
   *
   * A fixed viewBox scaled by CSS rather than a measured pixel size: the chart has to redraw
   * correctly in a print stylesheet and in a screenshot for a paper, and a geometry that depends on
   * the element's rendered width does neither.
   *
   * **Two boxes rather than one scaled**, for `series-chart`'s reason: a 760-unit box rendered in a
   * third of a column would draw its 11-unit axis text at a third of 11 pixels. Stating a smaller box
   * keeps the text the same size on screen and lets only the picture shrink.
   */
  readonly box = computed<CurveBox>(() =>
    this.size() === 'large'
      ? { width: 760, height: 320, left: 48, top: 16, right: 16, bottom: 44 }
      : { width: 380, height: 190, left: 34, top: 10, right: 10, bottom: 32 },
  );

  private readonly scale = computed(() => scaleFor(this.box(), this.horizonPeriods()));

  readonly axisLabel = computed(() => periodAxisLabel(this.periodLength()));

  readonly isLarge = computed(() => this.size() === 'large');

  readonly viewBox = computed(() => `0 0 ${this.box().width} ${this.box().height}`);

  readonly disruptedPath = computed(() =>
    linePath(this.points(), this.scale(), (point) => point.fill),
  );

  readonly baselinePath = computed(() =>
    linePath(this.points(), this.scale(), (point) => point.baselineFill),
  );

  readonly regionPaths = computed(() =>
    this.regions().map((region) => regionPath(region, this.scale())),
  );

  /** Thinned harder when small, for the reason `series-chart.periodTicks` gives. */
  readonly xTicks = computed(() => {
    const scale = this.scale();
    return periodTicks(this.horizonPeriods(), this.isLarge() ? 8 : 4).map((period) => ({
      period,
      x: scale.x(period),
    }));
  });

  readonly yTicks = computed(() => {
    const scale = this.scale();
    return FILL_TICKS.map((fill) => ({
      fill,
      y: scale.y(fill),
      label: `${Math.round(fill * 100)}%`,
    }));
  });

  /**
   * Where the cursor's line goes, or null where there is none.
   *
   * Clamped into the horizon rather than trusted: this input comes from a store that clamps already
   * (`period-cursor.clampPeriod`), and a line drawn outside the plot area would be the one visible
   * symptom of any future caller that does not.
   */
  readonly cursorAt = computed<number | null>(() => {
    const period = this.cursorPeriod();
    if (period === null || this.isEmpty()) {
      return null;
    }
    const last = Math.max(0, this.horizonPeriods() - 1);
    return this.scale().x(Math.min(Math.max(0, Math.floor(period)), last));
  });

  /** Where the triangle opens - marked so the reader can find the onset without counting ticks. */
  readonly onset = computed(() => {
    const period = onsetPeriod(this.regions());
    return period === null ? null : { period, x: this.scale().x(period) };
  });

  readonly plotRight = computed(() => this.box().width - this.box().right);
  readonly plotBottom = computed(() => this.box().height - this.box().bottom);

  /** "period 12 · 12 days in" - the onset marker's tooltip. */
  readonly onsetLabel = computed(() => {
    const onset = this.onset();
    if (!onset) {
      return '';
    }
    const period = this.periodLength();
    const elapsed = formatUnits({ value: onset.period * period.value, unit: period.unit });
    return `Performance first falls below baseline at period ${onset.period} (${elapsed} in)`;
  });

  readonly isEmpty = computed(() => this.points().length === 0);
}
