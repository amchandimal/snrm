import { DatePipe, DecimalPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { combineLatest, map } from 'rxjs';

import {
  MetricCard,
  formatCurrency,
  formatMetricValue,
  toMetricCard,
} from '../../../core/metric-display';
import { Id, TabularExportFormat } from '../../../core/models';
import { DiscardConfirm, discardRunConfirm } from '../../../core/run-discard';
import { isTextEntry } from '../../../core/text-entry';
import { formatNumber } from '../../../core/time-units';
import { CiValueComponent } from '../../../shared/ci-value/ci-value.component';
import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { MetricBadgeComponent } from '../../../shared/metric-badge/metric-badge.component';
// Cross-feature, into a pure module with a spec - the `disruption-overlay.ts → timeline.ts`
// precedent this feature already takes for the palette and the step geometry. `network-series.ts`
// is the derivation of the seven per-period quantities from `RUN_TIMESERIES` that the editor's
// network dashboard prints at its playback clock; this page prints them at its cursor, and one run
// must not have two derivations of "cost through period 14" (FR-19, FR-22).
import { networkSeries } from '../../network-editor/network-series';
import { ChartSize, SeriesChart, chartId } from '../element-charts';
import { ElementChartsComponent } from '../element-charts/element-charts.component';
import { FILL_RATE_CHART, NO_NETWORK_BASELINE, networkCharts } from '../network-charts';
import { NetworkInspectorComponent } from '../network-inspector/network-inspector.component';
import { NetworkFigures, networkFiguresAt } from '../period-cursor';
import { PeriodCursorComponent } from '../period-cursor/period-cursor.component';
import { PerformanceCurveComponent } from '../performance-curve/performance-curve.component';
import { ResultsExportService } from '../results-export.service';
import { CriticalityRow, RunResultsStore } from '../run-results.store';
import { SeriesChartComponent } from '../series-chart/series-chart.component';

/** One of the network scope's per-period figures, restated at the cursor (FR-22). */
interface CursorFigure {
  readonly key: string;
  readonly label: string;
  /** Already written the way its quantity reads; `-` where the run has no number for the period. */
  readonly value: string;
  /** One sentence under the figure, or null. */
  readonly hint: string | null;
}

/**
 * The results dashboard (FR-07, FR-08).
 *
 * > "Results dashboard - fill-rate-vs-period curve with baseline overlay and shaded loss area (the
 * > resilience triangle rendered literally); metric cards with CIs; per-node criticality table."
 *
 * Three panels, in that order, because that is the order the reading goes: the shape of what
 * happened, the numbers that summarise it, and then where in the network the fragility sits.
 *
 * ## The page has a scope (FR-22)
 *
 * The top-left corner now holds the **network inspector**, and clicking a node, a link or empty space
 * in it sets what the page describes. The network scope is this dashboard exactly as it was - the
 * curve, the cards, the criticality table - and an element scope replaces the curve area with that
 * element's per-period series as full charts. Everything else on the page is untouched by the scope,
 * deliberately: the metric suite's scalars are horizon figures with confidence intervals and have no
 * element form, and the criticality table is a property of the network rather than of any one node.
 *
 * **The criticality table and the miniature are one selection in two representations.** A row click
 * writes the same signal a dot click writes, and the row that names the scoped node is marked. Two
 * selections that could disagree would be two answers to "which node is this page about", on one
 * screen.
 *
 * ## One cursor through everything (FR-22)
 *
 * > "A period cursor - scrub and step controls shared by every chart on the page - moves back and
 * > forth through the horizon: every chart carries the cursor line, every per-period figure restates
 * > itself at the cursor's period, and the miniature tints availability and fill at that period."
 *
 * The transport sits above the panels rather than inside one of them, because it governs all of
 * them: the performance curve's line, an element scope's eight charts, the figures under the curve
 * and the miniature's tints are one period, read from one signal on `RunResultsStore`. The **arrow
 * keys** are here rather than on the transport for the same reason - a reader looking at a chart at
 * the bottom of the page must be able to step without first clicking the bar - and they are guarded
 * exactly as the editor guards its own transport keys, through the `core/text-entry.ts` both now
 * share: never while focus is in a form control, which is what leaves the scrub slider's native
 * arrow-key handling to the slider.
 *
 * **Nothing on this page animates.** There is no play control, no timer and no
 * `requestAnimationFrame`: the dashboard navigates a run, and animating one is playback on the
 * editor's canvas - one such loop in the whole application, not two.
 *
 * ## Time-valued metrics are shown both ways
 *
 * `TTR` is a count of *this network's* periods. The card shows "14 periods (14 days)" rather than a
 * bare 14 - the period count ties the figure to a column of the time series a reader can go and
 * look at, and the duration is what makes it mean anything to someone who does not hold the
 * network's clock in their head. The conversion is `core/metric-display.ts`, which multiplies by the
 * period's *value* as well as reading its unit; a network stepping in 2 DAY reports 14 periods as 28
 * days, and reading the unit alone would silently say 14.
 *
 * ## The shaded area and the LOSS_AREA card are not the same number
 *
 * They answer the same question about different objects, and the dashboard says so rather than
 * quietly showing two figures that disagree. See `curve-geometry.ts`.
 */
@Component({
  selector: 'app-results-dashboard',
  standalone: true,
  imports: [
    DatePipe,
    DecimalPipe,
    RouterLink,
    CiValueComponent,
    ConfirmDialogComponent,
    ElementChartsComponent,
    MetricBadgeComponent,
    NetworkInspectorComponent,
    PeriodCursorComponent,
    PerformanceCurveComponent,
    SeriesChartComponent,
  ],
  templateUrl: './results-dashboard.component.html',
  styleUrl: './results-dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultsDashboardComponent {
  readonly store = inject(RunResultsStore);
  readonly exports = inject(ResultsExportService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly params = toSignal(
    combineLatest([this.route.paramMap, this.route.queryParamMap]).pipe(
      map(([path, query]) => ({
        projectId: Number(path.get('projectId')) || null,
        runId: Number(path.get('runId')) || null,
        jobId: query.get('jobId'),
      })),
    ),
    { initialValue: { projectId: null, runId: null, jobId: null } },
  );

  readonly projectId = computed(() => this.params().projectId);
  readonly runId = computed(() => this.params().runId);

  readonly run = this.store.run;
  readonly periodLength = this.store.periodLength;

  /**
   * The metric cards, resolved.
   *
   * In the order the API returned them, which is the registry's own suite order - a
   * re-sort here would be a second opinion about how the suite reads.
   */
  readonly cards = computed<readonly MetricCard[]>(() => {
    const period = this.periodLength();
    return this.store.metricCards().map((metric) => toMetricCard(metric, period));
  });

  readonly structural = computed<readonly MetricCard[]>(() =>
    this.store.structuralMetrics().map((metric) => toMetricCard(metric, this.periodLength())),
  );

  /** The area of the polygon actually drawn, for the caption beneath the chart. */
  readonly shadedArea = this.store.shadedArea;

  readonly hasLoss = computed(() => this.store.lossRegions().length > 0);

  /** True when the run's parameters make it exactly reproducible by hand. */
  readonly isDeterministic = computed(() => {
    const params = this.run()?.params;
    return !!params && params.demandNoiseCv === 0 && params.timingJitterPeriods === 0;
  });

  /** The undisrupted baseline run of FR-17 - no scenario, no paired set, four metrics absent. */
  readonly isBaselineRun = computed(() => {
    const run = this.run();
    return run !== null && run.scenarioId === null;
  });

  // ------------------------------------------------------------------- the scope (FR-22)

  /** True for the dashboard as it always was: the curve, not an element's charts. */
  readonly isNetworkScope = this.store.isNetworkScope;

  /**
   * Reveal a node in the miniature - the criticality table's row click.
   *
   * The same gesture the network dashboard's own criticality rows make in the editor, writing the
   * same signal the miniature writes. One selection, two representations.
   */
  selectNode(row: CriticalityRow): void {
    this.store.selectNode(row.nodeId);
  }

  /** Whether a criticality row names the node the page is scoped to - the row is marked, not moved. */
  isScopedNode(row: CriticalityRow): boolean {
    const scope = this.store.scope();
    return scope.kind === 'node' && scope.id === row.nodeId;
  }

  // ------------------------------------------------------------ the period cursor (FR-22)

  /** True when this run has periods to move through - what renders the transport. */
  readonly hasCursor = this.store.hasCursor;

  /** The one period every chart on this page draws its line at, or null where there is no cursor. */
  readonly cursorPeriod = computed<number | null>(() =>
    this.store.hasCursor() ? this.store.cursorPeriod() : null,
  );

  /**
   * The run's per-period curve, split into the quantities the figures print.
   *
   * `network-series.ts`, the editor's own derivation, imported rather than re-implemented - see the
   * import note. Recomputed per **run**, not per period: the cursor reads one index out of these
   * arrays, so stepping re-runs {@link cursorFigures} and not this.
   */
  private readonly series = computed(() => networkSeries(this.store.results()?.timeseries ?? []));

  private readonly figures = computed<NetworkFigures | null>(() =>
    this.store.hasCursor() ? networkFiguresAt(this.series(), this.store.cursorPeriod()) : null,
  );

  // ---------------------------------------------------- the network scope's charts

  /**
   * The six per-period series the network scope charts beside its curve.
   *
   * The seventh - the fill rate - is the performance curve itself, which keeps its own component:
   * its axis is pinned to [0, 1] and the region between it and its baseline is the resilience
   * triangle rendered literally, which is not a step chart (`network-charts.ts`).
   *
   * Recomputed per **run**, never per period: the cursor moves a line and a figure, not a geometry.
   */
  readonly networkChartSet = computed<readonly SeriesChart[]>(() => networkCharts(this.series()));

  /** The curve's identity in the page's expanded set - it takes part in the gesture like the rest. */
  readonly curveChart = FILL_RATE_CHART;

  /** Printed once beneath the set, for on-hand and pipeline (`RUN_TIMESERIES` records no twin). */
  readonly noBaselineNote = NO_NETWORK_BASELINE;

  /**
   * Whether a chart of *this scope* is enlarged.
   *
   * Scoped through `chartId`, so the network's `onHand` chart and a node's are two different charts
   * that happen to share a key.
   */
  isChartExpanded(key: string): boolean {
    return this.store.isChartExpanded(chartId('network', key));
  }

  chartSize(key: string): ChartSize {
    return this.isChartExpanded(key) ? 'large' : 'small';
  }

  /** The double-click: enlarge a chart, or put it back in the grid. */
  toggleChart(key: string): void {
    this.store.toggleChartSize(chartId('network', key));
  }

  chartHint(key: string, label: string): string {
    return this.isChartExpanded(key)
      ? `Double-click to put ${label} back in the grid`
      : `Double-click to enlarge ${label}`;
  }

  /**
   * The network scope's per-period figures, restated at the cursor (FR-22, and FR-19's own list).
   *
   * Service first, then money, then what the network is holding - the order the editor's network
   * dashboard prints the same seven in, and the order of the metric families. The
   * sentences under them are that panel's, because the figures are the same figures: a researcher
   * reading period 11 on the canvas and period 11 here is reading one run, and the two must agree
   * digit for digit.
   *
   * Two **labels** deliberately differ from that panel's - *Cost to this period* and *Unmet demand to
   * this period*, where the canvas says "Cumulative". There, "cumulative" can only mean "through the
   * period the clock has reached"; here the reader chooses where it stops, and a total that silently
   * moved with a cursor while calling itself cumulative would invite being read as the run's.
   *
   * Every one of them is `-` where the run has no number for the period rather than 0, which is
   * `network-series.valueAtPeriod`'s rule and the reason these are strings by the time they reach
   * the template.
   */
  readonly cursorFigures = computed<readonly CursorFigure[]>(() => {
    const figures = this.figures();
    if (figures === null) {
      return [];
    }
    return [
      {
        key: 'fill',
        label: 'Fill rate',
        value: figures.fill === null ? '-' : formatMetricValue(figures.fill, 'ratio'),
        // Stated rather than dashed, because the curve above is drawn at 100% for this period: the
        // engine reads a period nobody demanded anything in as fully served, and a figure that
        // disagreed with the line it stands under would be the drift the shared cursor exists to
        // prevent (see `period-cursor.networkFiguresAt`).
        hint: figures.noDemand
          ? 'No demand arose this period, so there was no fraction to fall short of - the engine '
            + 'reads a quiet period as fully served, and the curve is drawn at 100% here.'
          : null,
      },
      {
        key: 'served',
        label: 'Served / demand',
        value: `${show(figures.served)} of ${show(figures.demand)}`,
        hint: null,
      },
      {
        key: 'cost',
        label: 'Period cost',
        value: money(figures.cost),
        hint: 'Fixed, variable, transport, holding and shortage, for this period alone.',
      },
      {
        key: 'cost-to-cursor',
        label: 'Cost to this period',
        value: money(figures.costToCursor),
        hint: 'Spent through this period, inclusive. At the last period it is the run’s TOTAL_COST.',
      },
      {
        key: 'unmet-to-cursor',
        label: 'Unmet demand to this period',
        value: show(figures.unmetToCursor),
        hint:
          'Lost, not backlogged - the engine carries no demand forward, so nothing later in the run '
          + 'makes this up.',
      },
      {
        key: 'on-hand',
        label: 'Total on-hand',
        value: show(figures.onHand),
        hint: 'Stock across the whole network at period end. Its horizon mean is AVG_INVENTORY.',
      },
      {
        key: 'in-pipeline',
        label: 'In pipeline',
        value: show(figures.inPipeline),
        hint: 'Material shipped and not yet arrived. Its horizon mean is AVG_PIPELINE.',
      },
    ];
  });

  /**
   * The arrow keys, on the page rather than on the transport (FR-22).
   *
   * **Guarded exactly as the editor guards its own transport keys**, through the module both now
   * share (`core/text-entry.ts`): never while focus is in a form control. That is what leaves the
   * scrub slider's own arrow keys to the slider - it already moves by one step, which is one period,
   * so a second step from here would move the cursor twice per keystroke - and what keeps the typed
   * phrase of the delete confirmation from scrubbing the run it is about to delete. The dialog is
   * excluded whole for the same reason the editor excludes its own: a modal owns the keyboard while
   * it is up, including the keys its buttons do not use.
   */
  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (this.deletePromptOpen() || !this.store.hasCursor()) {
      return;
    }
    // A modified arrow is a browser gesture - word-wise caret movement, back/forward navigation -
    // and stepping a period on top of one would be taking a keystroke that was not offered.
    if (event.ctrlKey || event.metaKey || event.altKey || isTextEntry(event.target)) {
      return;
    }
    if (event.key === 'ArrowRight') {
      event.preventDefault();
      this.store.stepCursor(1);
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      this.store.stepCursor(-1);
    }
  }

  // ------------------------------------------------------------ delete the run (FR-20)

  /** Whether the typed confirmation is up. */
  readonly deletePromptOpen = signal(false);

  /**
   * The run this page has just discarded, so the loading effect does not fetch it straight back.
   *
   * Set *before* the request rather than after it: a route event landing between the two would
   * otherwise re-open a run the server has just deleted and paint a 404 over a successful action.
   * Cleared again if the delete failed, which leaves the page exactly as it was - {@link openedRunId}
   * still names this run, so nothing re-reads it and the server's own sentence stays on screen with
   * the Cancel button beside it.
   */
  private readonly deletedRunId = signal<Id | null>(null);

  /** The single-run confirmation, from the same specced module the editor's discard uses. */
  readonly deleteConfirm = computed<DiscardConfirm | null>(() => {
    const record = this.run();
    return record === null ? null : discardRunConfirm(record);
  });

  requestDeleteRun(): void {
    if (this.run() !== null) {
      this.deletePromptOpen.set(true);
    }
  }

  /**
   * Deletes the run and leaves the page, because this route no longer resolves.
   *
   * A `RUN_ACTIVE` 409 is a real possibility here - the dashboard opens on a `QUEUED` or `RUNNING`
   * run by design - and it leaves the page exactly where it was, with the server's own
   * sentence in the error banner and the Cancel button beside it, which is the remedy that sentence
   * names.
   */
  async confirmDeleteRun(): Promise<void> {
    const runId = this.runId();
    if (runId === null) {
      return;
    }
    this.deletePromptOpen.set(false);
    this.deletedRunId.set(runId);
    if (await this.store.deleteRun()) {
      await this.router.navigate(['/projects', this.projectId() ?? 0, 'simulations']);
      return;
    }
    this.deletedRunId.set(null);
  }

  /**
   * The run this component instance has already opened.
   *
   * Deliberately a plain field rather than `store.runId()`. {@link RunResultsStore} is
   * `providedIn: 'root'` and outlives the route, so its run id answers "what did it last point at",
   * not "have I opened this" - and reading the page is exactly the moment to re-read the run
   * (see `RunResultsStore.open`). The dedupe therefore belongs to the **component instance**, one of
   * which exists per route entry: every entry refreshes, and a re-run of the effect within one entry
   * costs nothing.
   */
  private openedRunId: Id | null = null;

  constructor() {
    // `allowSignalWrites` for the reason the network editor's loading effect carries it: opening a
    // run sets the store's state signals synchronously, and that is this effect's whole purpose
    // rather than an accident of ordering. Without it Angular 18 refuses the write (`NG0600`,
    // `signalSetFn` → `producerUpdatesAllowed`) - and because the write happens inside an `async`
    // method, the refusal surfaces as an *unhandled rejection* rather than as a failing effect. The
    // page then renders its breadcrumb over an empty state, `GET /simulations/{runId}` is never
    // issued, and nothing on screen says why. `run-results.store.spec.ts` pins the store's half of
    // this; the flag is the caller's half, and both are here because either alone is a trap.
    effect(
      () => {
        const { runId, jobId } = this.params();
        if (runId === null || runId === this.deletedRunId() || runId === this.openedRunId) {
          return;
        }
        this.openedRunId = runId;
        // The job id arrives on the URL the launcher navigated to, since only the submitter is ever
        // told one. Without it the page still works and offers a refresh button instead.
        this.store.open(runId, jobId);
      },
      { allowSignalWrites: true },
    );
  }

  refresh(): void {
    void this.store.refresh();
  }

  cancel(): void {
    void this.store.cancel();
  }

  export(format: TabularExportFormat): void {
    const runId = this.runId();
    if (runId !== null) {
      void this.exports.downloadRun(runId, format);
    }
  }

  editorLink(networkId: Id): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'networks', networkId, 'editor'];
  }

  percent(value: number): string {
    return `${(value * 100).toFixed(1)}%`;
  }
}

/** A quantity as the cursor's figures print it; `-` where the run has no number for the period. */
function show(value: number | null): string {
  return value === null ? '-' : formatNumber(value);
}

/** Money, through the one formatter - no currency symbol, because the model has no currency. */
function money(value: number | null): string {
  return value === null ? '-' : formatCurrency(value);
}
