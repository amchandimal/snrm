import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { MetricCode, MetricResult } from '../../../core/models';
import { NetworkEditorStore } from '../network-editor.store';
import { CriticalityRow, TopologicalMetricsStore } from '../topological-metrics.store';

/** One row of the network-scoped section: what to show, and what it means. */
interface MetricRow {
  readonly code: MetricCode;
  readonly label: string;
  readonly formatted: string;
  /** One sentence, from the definition. Shown as a tooltip and under the value. */
  readonly meaning: string;
}

/**
 * Human labels and one-line readings for the topological suite.
 *
 * Deliberately a lookup with a fallback rather than a closed map: the backend's registry discovers
 * calculators at runtime, so a code this build has never seen is a metric someone added,
 * and rendering its code and value beats hiding it.
 *
 * **Written in suite order, and that order is not read from here.** Rows are ranked by
 * `TOPOLOGICAL_METRIC_CODES` in `TopologicalMetricsStore.networkMetrics` - a lookup has no order to
 * read - but a reader of this file would infer one from the entries, so the two are kept agreed:
 * density, then the three single-point-of-failure figures, then the rest.
 */
const DESCRIPTORS: Readonly<
  Record<string, { label: string; meaning: string; format: 'ratio' | 'count' | 'hops' }>
> = {
  DENSITY: {
    label: 'Density',
    meaning: 'Of all the arcs that could exist between these nodes, the fraction that do.',
    format: 'ratio',
  },
  SPOF_NODE_COUNT: {
    label: 'Single points of failure - nodes',
    meaning:
      'Facilities whose loss alone cuts some customer off from all supply. The remedy is a second site or a second qualified source.',
    format: 'count',
  },
  SPOF_ARC_COUNT: {
    label: 'Single points of failure - arcs',
    meaning:
      'Lanes whose loss alone cuts some customer off from all supply. The remedy is a second route or a second carrier - the facilities are already there.',
    format: 'count',
  },
  SPOF_COUNT: {
    label: 'Single points of failure - total',
    meaning:
      'Nodes and arcs whose loss alone cuts some customer off from all supply. Zero is the target; the two rows above say which kind to spend on.',
    format: 'count',
  },
  AVG_PATH: {
    label: 'Average path length',
    meaning: 'Mean number of arcs between a pair of nodes that are connected at all.',
    format: 'hops',
  },
  CLUSTERING: {
    label: 'Clustering',
    meaning:
      'How often two partners of the same node are also connected - local alternative routes.',
    format: 'ratio',
  },
  ROBUSTNESS_RANDOM: {
    label: 'Robustness - random',
    meaning:
      'Area under the largest-component curve as nodes fail at random. Higher holds together longer; ½ is the ceiling.',
    format: 'ratio',
  },
  ROBUSTNESS_TARGETED: {
    label: 'Robustness - targeted',
    meaning:
      'The same curve with the most critical nodes removed first. Below the random figure means the fragility is concentrated.',
    format: 'ratio',
  },
};

/**
 * The metrics side panel.
 *
 * > "node colour encodes type, node size can encode live `NODE_CRITICALITY` so structural weak
 * > points are visible while editing."
 *
 * Two sections. The network-scoped metrics on top, each with the sentence that says how to read it -
 * a bare "0.472" is not a finding, and this suite exists precisely because the SLR found measurement
 * fragmented across families nobody converts between (RQ5). The per-node criticality table
 * below, worst first, with the node-size toggle beside it.
 *
 * Everything here is derived from {@link TopologicalMetricsStore}; the component computes no metric
 * and requests nothing on its own. Clicking a criticality row selects that node on the canvas, the
 * same gesture the resolution-warning banner uses.
 */
@Component({
  selector: 'app-metrics-panel',
  standalone: true,
  templateUrl: './metrics-panel.component.html',
  styleUrl: './metrics-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricsPanelComponent {
  readonly store = inject(TopologicalMetricsStore);
  private readonly editor = inject(NetworkEditorStore);

  readonly rows = computed<readonly MetricRow[]>(() =>
    this.store.networkMetrics().map((metric) => describe(metric)),
  );

  readonly criticalityRows = this.store.criticalityRows;

  /** "computed in 12 ms", or null before the first successful computation. */
  readonly timing = computed(() => {
    const ms = this.store.computedInMs();
    return ms === null ? null : `computed in ${ms} ms`;
  });

  refresh(): void {
    void this.store.refresh();
  }

  toggleSizeByCriticality(): void {
    this.store.toggleSizeByCriticality();
  }

  /** Reveal the node a table row names, so the criticality can be acted on. */
  select(row: CriticalityRow): void {
    this.editor.select([row.nodeId], []);
  }

  /** Per-node criticality as a percentage, for the table cell. */
  percent(value: number): string {
    return `${(value * 100).toFixed(1)}%`;
  }
}

function describe(metric: MetricResult): MetricRow {
  const descriptor = DESCRIPTORS[metric.metricCode];
  if (!descriptor) {
    return {
      code: metric.metricCode,
      label: metric.metricCode,
      formatted: formatNumber(metric.value),
      meaning: 'A metric this build does not have a description for (calculators are '
        + 'discovered at runtime).',
    };
  }
  return {
    code: metric.metricCode,
    label: descriptor.label,
    formatted: format(metric.value, descriptor.format),
    meaning: descriptor.meaning,
  };
}

function format(value: number, as: 'ratio' | 'count' | 'hops'): string {
  switch (as) {
    case 'count':
      return String(Math.round(value));
    case 'hops':
      // Two decimals: an average path length of 1.62 arcs is a real distinction from 1.6, and a
      // third decimal would be reporting precision the reader cannot use.
      return `${value.toFixed(2)} hops`;
    case 'ratio':
      return value.toFixed(4);
  }
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(4);
}
