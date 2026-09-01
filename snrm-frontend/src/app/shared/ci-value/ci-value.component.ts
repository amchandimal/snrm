import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatMetricValue, MetricFormat } from '../../core/metric-display';
import { Duration } from '../../core/models';

/**
 * A value with its 95% confidence interval across Monte Carlo replications.
 *
 * > "metric cards with CIs"
 *
 * Renders `0.82 [0.79 – 0.85]`, plus a **whisker**: a horizontal bar showing the interval to scale
 * with the mean marked on it. The number and the picture answer different questions - the number is
 * the result, the whisker is how much to trust it - and a resilience study that reports a fill rate
 * of 0.82 without saying whether the interval is ±0.01 or ±0.3 has reported a coin toss as a finding.
 *
 * **Degrades to the bare value, and that is meaningful.** Topological metrics are exact and carry no
 * interval; so does `CVAR_COST`, which is a functional of the whole replication set rather
 * than a mean of per-replication values, so an interval computed the way the others are would
 * overstate its precision. Both arrive with `ciLow`/`ciHigh` null and are shown as a plain number
 * with no whisker - never as a zero-width interval, which would claim certainty the metric does not
 * have.
 *
 * The whisker is scaled against a caller-supplied range rather than its own bounds, so that a row of
 * cards or a column of table cells share one scale and can be compared by eye. Without a range it
 * falls back to the interval itself, which still shows asymmetry.
 */
@Component({
  selector: 'app-ci-value',
  standalone: true,
  templateUrl: './ci-value.component.html',
  styleUrl: './ci-value.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CiValueComponent {
  readonly value = input.required<number>();
  readonly ciLow = input<number | null>(null);
  readonly ciHigh = input<number | null>(null);

  /** How the number is written; defaults to a plain decimal. */
  readonly format = input<MetricFormat>('decimal');

  /** The network's clock, needed only by a `periods` metric. */
  readonly periodLength = input<Duration | undefined>(undefined);

  /** Draw the interval bar. Off in dense table cells, where the text alone is enough. */
  readonly whisker = input(false);

  /** Shared axis for the whisker, so several cards compare by eye. */
  readonly scaleMin = input<number | null>(null);
  readonly scaleMax = input<number | null>(null);

  readonly formatted = computed(() =>
    formatMetricValue(this.value(), this.format(), this.periodLength()),
  );

  /** True only when both bounds arrived - a half interval is not an interval. */
  readonly hasInterval = computed(() => this.ciLow() !== null && this.ciHigh() !== null);

  readonly formattedLow = computed(() => {
    const low = this.ciLow();
    return low === null ? '' : formatMetricValue(low, this.format(), this.periodLength());
  });

  readonly formattedHigh = computed(() => {
    const high = this.ciHigh();
    return high === null ? '' : formatMetricValue(high, this.format(), this.periodLength());
  });

  /**
   * The whisker geometry, as percentages of the track.
   *
   * Null when there is nothing to draw - no interval, or a degenerate scale. A zero-width track
   * would put the mean marker at an arbitrary position and invite the reader to compare it with the
   * marker beside it.
   */
  readonly bar = computed(() => {
    const low = this.ciLow();
    const high = this.ciHigh();
    if (low === null || high === null) {
      return null;
    }
    const min = this.scaleMin() ?? Math.min(low, this.value());
    const max = this.scaleMax() ?? Math.max(high, this.value());
    const span = max - min;
    if (!Number.isFinite(span) || span <= 0) {
      return null;
    }
    const clamp = (fraction: number) => Math.max(0, Math.min(100, fraction * 100));
    const left = clamp((low - min) / span);
    const right = clamp((high - min) / span);
    return {
      left,
      // A hair of width so an interval far narrower than the shared scale still renders as a mark
      // rather than vanishing - the case worth seeing, since it means the estimate is tight.
      width: Math.max(right - left, 0.6),
      mean: clamp((this.value() - min) / span),
    };
  });
}
