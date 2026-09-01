import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { Duration } from '../../../core/models';
import {
  ChartSize,
  NO_BASELINE_COLUMN,
  SeriesChart,
  chartId,
  inboundFlowFor,
  linkCharts,
  nodeCharts,
} from '../element-charts';
import { RunResultsStore } from '../run-results.store';
import { SeriesChartComponent } from '../series-chart/series-chart.component';

/**
 * The element scope's charts - what replaces the curve when a node or a link is selected
 * (FR-22).
 *
 * > "an element scope replaces the curve area with that element's own series as full stepwise
 * > charts … each with its paired-baseline overlay, under the FR-18 disciplines (nulls break the
 * > line, absence is a sentence, `available: false` degrades to the stated message and never to
 * > zeros)."
 *
 * ## Three states, and only one of them is a chart
 *
 * A run may have **no per-element series at all** - recorded before `V9__element_timeseries.sql`, or
 * submitted with `recordElementTimeseries: false` - and that is not an error and not a page of
 * zeros. It shows the sentence `core/element-series.ts` holds, the same one the editor's
 * transport bar and element inspector show, and the network scope beside it keeps working in full:
 * the curve, the metric cards and the criticality table read `RUN_TIMESERIES` and the topological
 * suite, neither of which this endpoint touches.
 *
 * A run may have a series that carries **no rows for the selected element** - the single-element
 * reading the API distinguishes, `available: true` with nothing in it - which is a different sentence
 * again.
 *
 * ## The composition has no spec, and its arithmetic does
 *
 * Every number on this surface comes from `element-charts.ts` or from
 * `network-editor/sparkline-geometry.ts`, both pure and both specced, and what is left here is signal
 * plumbing. That is the rule the element inspector and the network dashboard already follow - a
 * component that is composition over signals earns no spec of its own.
 */
@Component({
  selector: 'app-element-charts',
  standalone: true,
  imports: [SeriesChartComponent],
  templateUrl: './element-charts.component.html',
  styleUrl: './element-charts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ElementChartsComponent {
  readonly store = inject(RunResultsStore);

  /** Printed once beneath the charts, for every chart in the set that has no overlay. */
  readonly noBaselineNote = NO_BASELINE_COLUMN;

  readonly node = this.store.scopedNode;
  readonly link = this.store.scopedLink;

  /**
   * The page's one cursor, or null where this run has none to carry (FR-22).
   *
   * Passed straight through to every chart rather than held here: an element scope draws eight of
   * them and they must all stand on the same period, which is what "one cursor, one period, every
   * surface" means. Null for a run with no series to move through - the charts then draw the whole
   * horizon exactly as they did before the cursor existed.
   */
  readonly cursorPeriod = computed<number | null>(() =>
    this.store.hasCursor() ? this.store.cursorPeriod() : null,
  );

  /** The **run's** clock, which every time-valued reading on this page is stated in. */
  readonly periodLength = computed<Duration | null>(() => this.store.run()?.periodLength ?? null);

  /** `DC-1` or `DC-1 → CUST-1`. */
  readonly title = computed<string>(() => {
    const node = this.node();
    if (node) {
      return node.name;
    }
    const link = this.link();
    if (!link) {
      return '';
    }
    const nodes = this.store.nodes();
    const source = nodes.find((entry) => entry.id === link.sourceNodeId)?.name ?? `#${link.sourceNodeId}`;
    const target = nodes.find((entry) => entry.id === link.targetNodeId)?.name ?? `#${link.targetNodeId}`;
    return `${source} → ${target}`;
  });

  readonly subtitle = computed<string>(() => {
    const node = this.node();
    return node ? `${node.type} · node #${node.id}` : `link #${this.link()?.id ?? ''}`;
  });

  /**
   * What the panel says instead of charts, or null when it has charts to draw.
   *
   * The first branch is the transport bar's own sentence, verbatim from `core/element-series.ts` -
   * two surfaces phrasing one situation differently read as two problems.
   */
  readonly note = computed<string | null>(() => {
    if (this.store.elementsLoading()) {
      return 'Reading this run’s element detail…';
    }
    // The scope names an element the structure has no row for - which in practice means the network
    // read failed, since every id here came from that read or from the criticality suite. Said
    // rather than left as a block headed by an empty name: the charts cannot be labelled either.
    if (this.node() === null && this.link() === null) {
      return 'This run’s network could not be read, so the selected element cannot be named.';
    }
    const stated = this.store.elementNote();
    if (stated !== null) {
      return stated;
    }
    if (this.periodLength() === null) {
      return 'This run has no clock recorded, so its per-period charts cannot be labelled.';
    }
    return this.charts().length === 0
      ? 'This run recorded no series for the selected element.'
      : null;
  });

  /**
   * The charts of the scoped element, in the order they are displayed.
   *
   * Recomputed per selection, never per period: the paths themselves are `series-chart`'s own
   * computed over this, which is the split the element inspector and the network dashboard both make
   * so that a cursor moving through the horizon re-runs the figures and not the geometry.
   */
  readonly charts = computed<readonly SeriesChart[]>(() => {
    const nodeSeries = this.store.scopedNodeSeries();
    if (nodeSeries) {
      // The weights of the inbound lead's mean. One derivation, shared with the editor's inspector,
      // so one node cannot be given two different average lead times on two screens.
      const weights = inboundFlowFor(nodeSeries.nodeId, this.store.links(), this.store.linkSeries());
      return nodeCharts(nodeSeries, weights);
    }
    const linkSeries = this.store.scopedLinkSeries();
    const link = this.link();
    if (!linkSeries || !link) {
      return [];
    }
    // Which of the two nulls a gap in `utilisation` is: no ceiling to be a fraction of, or a ceiling
    // an outage took to zero. Only the network's own row can answer it.
    return linkCharts(linkSeries, link.capacity.value !== null);
  });

  /** True when at least one chart in the set is drawn bare - the footnote is shown only then. */
  readonly hasBareChart = computed(() =>
    this.charts().some((chart) => chart.baseline === null && chart.suppressed === null),
  );

  // -------------------------------------------------------------- enlarging a chart

  /**
   * Whether a chart of *this scope* is enlarged, and the gesture that changes it.
   *
   * The same double-click the network scope's charts take, through the same store signal, because
   * one gesture that worked on one grid and not the other would read as a defect in whichever the
   * reader tried second. Scoped through `chartId`: a node's `onHand` and the network's are two
   * charts, and enlarging one must not enlarge the other when the scope changes.
   */
  isChartExpanded(key: string): boolean {
    return this.store.isChartExpanded(chartId('element', key));
  }

  chartSize(key: string): ChartSize {
    return this.isChartExpanded(key) ? 'large' : 'small';
  }

  toggleChart(key: string): void {
    this.store.toggleChartSize(chartId('element', key));
  }

  chartHint(key: string, label: string): string {
    return this.isChartExpanded(key)
      ? `Double-click to put ${label} back in the grid`
      : `Double-click to enlarge ${label}`;
  }

  backToNetwork(): void {
    this.store.selectNetwork();
  }
}
