import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { DisruptionEvent, Id, Network } from '../../../core/models';
import { formatDuration, formatSpan, horizonSeconds } from '../../../core/time-units';
import { AxisTick, TimelineBar, TimelineRow, axisTicks } from '../timeline';

/**
 * The Gantt chart: rows are targeted nodes, links and regions; bars are events.
 *
 * > "Scenario builder - timeline: rows are targeted nodes/links/regions, bars are events
 * > (start/duration), with severity and recovery profile per bar."
 *
 * Presentational. It receives rows already laid out by `timeline.ts` and emits which bar was
 * clicked; the store owns the data and the editor beside it owns the writing. That split is what
 * lets the geometry be unit-tested without a component fixture.
 *
 * ## What a bar shows, and in which units
 *
 * **Position is in periods; the label is in the declared unit.** The bar is placed by converting the
 * event's `startOffset` and `duration` onto the selected network's clock - that is the
 * only way to draw it, since the axis counts simulation steps. The text on it is what the user
 * typed: `4 wk → 10 d`, never `28 → 7`. Switch the network in the picker above and every bar moves
 * while every label stays put, which is precisely the property that makes a project-scoped scenario
 * comparable across variants.
 *
 * **Severity is encoded twice**, in the bar's opacity and in a number on it. The encoding makes the
 * shape of a scenario readable at a glance - three light bars and one solid one is a story - and the
 * number is there because opacity is not a scale anyone can read a value off.
 *
 * **A probabilistic event is drawn dashed.** An event with `probability < 1` does not occur in every
 * replication, and a chart that drew it identically to a certain one would be overstating
 * what the scenario says.
 */
@Component({
  selector: 'app-event-timeline',
  standalone: true,
  templateUrl: './event-timeline.component.html',
  styleUrl: './event-timeline.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventTimelineComponent {
  readonly rows = input.required<readonly TimelineRow[]>();
  /** The network the bars are placed against - its clock and horizon are the axis. */
  readonly network = input.required<Network>();
  /** Which bar is open in the editor, so the chart can show the selection. */
  readonly selectedEventId = input<Id | null>(null);

  readonly barSelected = output<DisruptionEvent>();
  readonly addRequested = output<void>();

  /** Gridlines, at a round number of periods. */
  readonly ticks = computed<readonly AxisTick[]>(() => axisTicks(this.network().horizonPeriods));

  /** `52 periods · 52 d` - the axis is in periods, and the wall clock is the second reading. */
  readonly horizonLabel = computed(() => {
    const network = this.network();
    const span = formatSpan(horizonSeconds(network.periodLength, network.horizonPeriods));
    return `${network.horizonPeriods} periods · ${span}`;
  });

  /** One period, as the user stated it - the denominator every bar was divided by. */
  readonly periodLabel = computed(() => formatDuration(this.network().periodLength));

  readonly isEmpty = computed(() => this.rows().length === 0);

  /**
   * Bar fill from severity: 30% alpha at severity 0, opaque at severity 1.
   *
   * Floored well above transparent so a low-severity event is still a bar rather than a smudge - the
   * encoding is meant to rank events, not to hide them.
   *
   * Returned as a whole colour rather than as a CSS custom property, so the binding is an ordinary
   * `[style.backgroundColor]` and the alpha is not carried through a variable the stylesheet also has
   * an opinion about. The text stays fully opaque either way, which `opacity` would not allow.
   */
  barBackground(bar: TimelineBar): string {
    const alpha = 0.3 + 0.7 * clamp01(bar.event.severity);
    return `rgba(13, 110, 253, ${alpha.toFixed(3)})`;
  }

  /** Everything about one bar, for its `title` - the declared timing, what it becomes, and the rest. */
  barTooltip(bar: TimelineBar): string {
    const event = bar.event;
    const parts = [
      `starts ${formatDuration(event.startOffset)} in, lasts ${formatDuration(event.duration)}`,
      `on this network: ${bar.periodLabel}`,
      `severity ${formatPercent(event.severity)} · ${event.recoveryProfile.toLowerCase()} recovery`,
    ];
    if (event.probability < 1) {
      parts.push(`occurs in ${formatPercent(event.probability)} of replications`);
    }
    if (bar.exceedsHorizon) {
      parts.push('ends after the horizon - its recovery is never observed');
    }
    return parts.join(' · ');
  }

  /** `60%` - severity and probability both read as percentages in the UI, as [0,1] on the wire. */
  percent(value: number): string {
    return formatPercent(value);
  }

  select(bar: TimelineBar): void {
    this.barSelected.emit(bar.event);
  }

  /** Space and Enter select a bar, which is a button and has to behave like one. */
  onBarKeydown(event: KeyboardEvent, bar: TimelineBar): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.select(bar);
    }
  }
}

function clamp01(value: number): number {
  return Math.min(Math.max(value, 0), 1);
}

function formatPercent(value: number): string {
  return `${Math.round(clamp01(value) * 100)}%`;
}
