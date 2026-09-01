import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import {
  formatMetricValue,
  formatTimeValued,
  periodAxisLabel,
} from '../../../core/metric-display';
import { Duration } from '../../../core/models';
import { formatNumber } from '../../../core/time-units';
import {
  SparkBox,
  cursorX,
  sparkline,
  valueY,
} from '../../network-editor/sparkline-geometry';
import { formatCurrency } from '../../../core/metric-display';
import { ChartSize, SeriesChart, periodTicksFor, readingAt, valueTicks } from '../element-charts';

/**
 * One element series as a full stepwise chart, with its paired baseline overlaid (FR-22).
 *
 * > "one element, whose per-period series render as full stepwise charts with
 * > paired-baseline overlays - charts, not only figures"
 *
 * ## It is the inspector's sparkline at chart size, and that is deliberate
 *
 * The path strings come from `network-editor/sparkline-geometry.sparkline`, the same module the
 * element inspector's 44-pixel line uses, through the four optional gutters it grew for the axes this
 * has and that one does not. Nothing about a step line changes with its size - a period is a band, a
 * riser marks a change, a null is a gap - so a second implementation of it could only ever be a
 * chance for the two surfaces to disagree about the same element of the same run. What the size buys
 * is what a sparkline cannot carry: a labelled value axis, a period axis, and room for the cursor to
 * come.
 *
 * ## The scale is fitted and the chart says so
 *
 * The opposite of the performance curve, and for the reason `sparkline-geometry` argues: a fill rate
 * has a meaningful floor and ceiling, so `curve-geometry.scaleFor` pins it to [0, 1] and the same
 * disruption reads the same on every variant. A stock or a flow has neither, and against a
 * zero-anchored axis every steady state flattens into the one thing the reader came to see. So the
 * axis is this element's own range, printed at both ends, and the baseline shares it - an overlay on
 * a different scale would be a picture of nothing.
 *
 * ## The period cursor, which is one signal bound to every chart on the page (FR-22)
 *
 * {@link cursorPeriod} still defaults to null and still draws nothing when it is - a chart rendered
 * outside the dashboard would carry no line. On the dashboard the page binds *one* store signal to
 * every chart, and each draws its line through the centre of that period's band (`cursorX`, already
 * the rule the period axis is labelled by, so the line falls through its own tick). One signal
 * rather than a cursor per chart is the requirement rather than an economy: the cursor is shared
 * by every chart, and two that could drift would put the same period in two places
 * on one screen.
 *
 * Beside the line, the head prints this series **at** the cursor. A period with no value prints the
 * short absence of `SeriesChart.absentReading` - *no inbound this period* - and never a 0, which is
 * the FR-18 discipline the whole chart is drawn under, said one more time where it is easiest to
 * break.
 */
@Component({
  selector: 'app-series-chart',
  standalone: true,
  templateUrl: './series-chart.component.html',
  styleUrl: './series-chart.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SeriesChartComponent {
  readonly chart = input.required<SeriesChart>();

  /** The **run's** clock - never the live network's, which a fork could have changed. */
  readonly periodLength = input.required<Duration>();

  /**
   * The period the shared cursor stands on, or null where the page has no cursor (FR-22).
   *
   * One signal for the whole page - see the class note.
   */
  readonly cursorPeriod = input<number | null>(null);

  /**
   * How large this chart is drawn - one of a grid, or enlarged.
   *
   * Defaults to `small`, because a page of per-period series is read by scanning the set and then
   * asking a question of one of them; the double-click that enlarges it is the parent's gesture, and
   * this input is all it needs to say.
   */
  readonly size = input<ChartSize>('small');

  /**
   * The drawing box, in the SVG's own user units.
   *
   * A fixed viewBox scaled by CSS, for the reason `performance-curve` gives: a geometry that depends
   * on the rendered width of a column that reflows at `xl` would redraw differently in a screenshot
   * for a paper than it does on screen. The left gutter holds a five-figure quantity and the bottom
   * one a row of period numbers, which is why this states four gutters where a sparkline states one.
   *
   * **The two sizes are two boxes, not one box scaled.** A small chart in a third of a column would
   * otherwise render its 10-unit axis text at a third of 10 pixels; stating a smaller box in user
   * units keeps the text the same size on screen at either size, and only the picture changes. The
   * gutters shrink with it - a small chart's value axis holds a shorter number because it has fewer
   * ticks to hold.
   */
  readonly box = computed<SparkBox>(() =>
    this.size() === 'large'
      ? { width: 640, height: 200, pad: 8, left: 54, right: 14, top: 12, bottom: 34 }
      : { width: 340, height: 132, pad: 6, left: 38, right: 8, top: 10, bottom: 26 },
  );

  readonly viewBox = computed(() => `0 0 ${this.box().width} ${this.box().height}`);

  /** The step paths and the frame everything else is placed on - per chart, never per period. */
  readonly spark = computed(() => {
    const chart = this.chart();
    return sparkline(chart.values, this.box(), chart.baseline ?? undefined);
  });

  readonly hasLine = computed(() => !this.spark().empty);

  readonly valueTicks = computed(() => valueTicks(this.spark()));

  /**
   * Period labels, thinned harder on a small chart.
   *
   * Eight labels under a 340-unit box would collide into a grey smear; four still say where the
   * horizon starts, ends and roughly divides, which is all a small chart is being asked. The
   * *choice* of periods is still `curve-geometry.periodTicks`, so both sizes tick whole periods and
   * the enlarged chart is the same axis with more of it shown.
   */
  readonly periodTicks = computed(() =>
    periodTicksFor(this.spark(), this.size() === 'large' ? 8 : 4),
  );

  readonly axisLabel = computed(() => periodAxisLabel(this.periodLength()));

  readonly plotRight = computed(() => {
    const spark = this.spark();
    return spark.left + spark.count * spark.slot;
  });

  readonly plotBottom = computed(() => {
    const spark = this.spark();
    return spark.top + spark.plotHeight;
  });

  /** Where the mean's reference line sits - the same number the caption prints (FR-18's rule). */
  readonly meanY = computed<number | null>(() => {
    const mean = this.chart().mean;
    const spark = this.spark();
    return mean === null || spark.empty ? null : valueY(mean, spark);
  });

  /**
   * Where the cursor's line goes, in this chart's own user units.
   *
   * A computed of its own, separate from {@link spark}: stepping the cursor re-runs this and leaves
   * the step paths exactly as they were, which is the split the element inspector and the network
   * dashboard both make so that moving through the horizon costs a line and not a geometry.
   */
  readonly cursorAt = computed<number | null>(() => {
    const period = this.cursorPeriod();
    const spark = this.spark();
    return period === null || spark.empty ? null : cursorX(period, spark);
  });

  /**
   * What this series reads **at** the cursor: a number, an absence in words, or nothing.
   *
   * Nothing where there is no cursor, and nothing where the chart itself was replaced by a sentence
   * (an uncapped arc has no utilisation at any period, so it has none at this one either). A period
   * the series has no value for prints `SeriesChart.absentReading` - the element inspector's own
   * three words - and falls back to an em dash for the series whose nulls are impossible, rather
   * than to a 0 that would be a claim (FR-18).
   */
  readonly cursorReading = computed<string | null>(() => {
    const period = this.cursorPeriod();
    const chart = this.chart();
    if (period === null || chart.suppressed !== null) {
      return null;
    }
    const value = readingAt(chart.values, period);
    return value === null ? (chart.absentReading ?? '-') : this.value(round2(value));
  });

  /** `mean 5.67`, `dispatch-weighted mean 1 period (1 day)`, or null where there is no number. */
  readonly meanText = computed<string | null>(() => {
    const chart = this.chart();
    return chart.mean === null
      ? null
      : `${chart.meanLabel} ${this.value(round2(chart.mean))}`;
  });

  /**
   * How many periods this series has no value for - what turns the absence sentence into a reading.
   *
   * "A gap is a period in which nothing was dispatched" is the rule; "3 of 30 periods" is what makes
   * a reader look at the right part of the line. Zero gaps hides the sentence altogether: a series
   * that happens to be complete on this run should not be annotated with a caveat about one that is
   * not.
   */
  readonly gapCount = computed(() => {
    let gaps = 0;
    for (const value of this.chart().values) {
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        gaps += 1;
      }
    }
    return gaps;
  });

  readonly periodCount = computed(() => this.chart().values.length);

  /** True while this chart is the enlarged one - the template shows its prose only then. */
  readonly isLarge = computed(() => this.size() === 'large');

  /**
   * The whole of what this chart says, as a tooltip.
   *
   * A small chart hides its explanatory sentence - eight of them in a grid is a wall of text where a
   * reader is scanning shapes - but the sentence is never *lost*: it is here on hover and printed in
   * full when the chart is enlarged. Nothing that is a claim about the data hides with it; the
   * absence count and the range caption stay at both sizes, because those say what the run recorded.
   */
  readonly tooltip = computed(() => {
    const chart = this.chart();
    return [chart.label, chart.hint].filter((part) => part !== null).join(' - ');
  });

  /**
   * The range the line is drawn against - stated, because the scale is fitted rather than pinned.
   *
   * A flat series prints **one** number, not the same number twice: `1 period (1 day)–1 period
   * (1 day)` reads as two facts where there is one, and a constant is exactly the finding a fitted
   * axis exists to show.
   */
  readonly rangeText = computed(() => {
    const spark = this.spark();
    if (spark.empty) {
      return '';
    }
    return spark.min === spark.max
      ? this.value(spark.min)
      : `${this.value(spark.min)}–${this.value(spark.max)}`;
  });

  /**
   * An axis label: compact, since the caption carries the units.
   *
   * Named `tickLabel` rather than `tick` because the template's `@for (tick of valueTicks())` binds
   * that name to the row, and a template local shadows the component member silently.
   */
  tickLabel(value: number): string {
    if (this.chart().unit === 'ratio') {
      return `${Math.round(value * 100)}%`;
    }
    // Money on an axis is rounded to whole units: `12,094.00` in a 38-unit gutter is unreadable, and
    // the caption and the at-cursor figure both carry the exact figure.
    return formatNumber(this.chart().unit === 'currency' ? Math.round(value) : value);
  }

  /**
   * A value as this chart writes it.
   *
   * A `periods` chart reads in both forms - `1 period (1 day)` - through the one module that turns a
   * period count into a duration, because the multiplication by the period's *value* is the slip
   * every 1-unit-period test network hides (`core/metric-display`).
   */
  value(value: number): string {
    switch (this.chart().unit) {
      case 'ratio':
        return formatMetricValue(value, 'ratio');
      case 'periods':
        return formatTimeValued(value, this.periodLength());
      case 'currency':
        // No symbol: the model has no currency, and printing one would invent it.
        return formatCurrency(value);
      default:
        return formatNumber(value);
    }
  }
}

/** Printed means are 2 dp: `170 / 30` is 5.67 in the caption and 5.6666… in the reference line. */
function round2(value: number): number {
  return Number(value.toFixed(2));
}
