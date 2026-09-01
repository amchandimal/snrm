import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { describeMetric, MetricFamily, METRIC_FAMILY_LABEL } from '../../core/metric-display';
import { MetricCode } from '../../core/models';

/**
 * One metric code as a compact badge, colour-coded by family.
 *
 * The six families exist because the SLR found resilience measurement fragmented across
 * literatures that do not convert between them (RQ5). A suite that presents fourteen
 * numbers in one undifferentiated list reproduces that problem on screen; colouring by family is the
 * cheapest way to make "this is a service metric and that is an economic one" visible without a
 * legend the reader has to hold in their head.
 *
 * A code this build has never seen gets the neutral `UNKNOWN` colour and its own code as its label -
 * calculators are discovered at runtime, so an unfamiliar code is a metric somebody added,
 * and hiding it would be the one response guaranteed to be wrong.
 */
@Component({
  selector: 'app-metric-badge',
  standalone: true,
  templateUrl: './metric-badge.component.html',
  styleUrl: './metric-badge.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricBadgeComponent {
  readonly code = input.required<MetricCode>();

  /** Show the human label rather than the raw code. On by default. */
  readonly labelled = input(true);

  readonly descriptor = computed(() => describeMetric(this.code()));
  readonly family = computed<MetricFamily>(() => this.descriptor().family);
  readonly familyLabel = computed(() => METRIC_FAMILY_LABEL[this.family()]);
  readonly text = computed(() => (this.labelled() ? this.descriptor().label : this.code()));

  /**
   * The tooltip: what the metric family is, and what the number means.
   *
   * The sentence comes from the definition. A bare "0.472" is not a finding, and the reader
   * of a dashboard should not have to open the design document to learn whether up is good.
   */
  readonly tooltip = computed(
    () => `${this.code()} · ${this.familyLabel()} - ${this.descriptor().meaning}`,
  );
}
