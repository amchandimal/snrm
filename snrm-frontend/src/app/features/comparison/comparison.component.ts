import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { LeverEntry, flattenLevers } from '../../core/lever-changes';
import {
  describeMetric,
  formatInUnit,
  formatMetricValue,
  formatUnits,
} from '../../core/metric-display';
import {
  ComparisonCell,
  ComparisonRow,
  ComparisonVariant,
  Id,
  MetricDirection,
  TabularExportFormat,
} from '../../core/models';
import { ResultsExportService } from '../simulations/results-export.service';
import { MetricBadgeComponent } from '../../shared/metric-badge/metric-badge.component';
import { ComparisonStore } from './comparison.store';
import { MetricRadarComponent } from './metric-radar/metric-radar.component';

/**
 * The comparison view (FR-10).
 *
 * > "Comparison view - variants × metrics matrix with best-in-row highlighting, radar chart of
 * > normalised metrics, and lever-change annotations from `lever_changes_json`."
 *
 * ## The lever annotations are the point
 *
 * A matrix without them reports that variant 3 recovers two periods faster. A matrix with them
 * reports that *+20% capacity at PLANT-1* recovers two periods faster - which is the claim a thesis
 * can make, and is exactly what `lever_changes_json` exists for. The annotation strip
 * sits directly under the column headers rather than in a tooltip, because a reader scanning the
 * matrix for a winner needs the cause in the same glance as the effect.
 *
 * The diff is free-form (the persistence layer stays agnostic of the lever vocabulary, and
 * Phase 2 owns it), so it is flattened generically here rather than being parsed against
 * a schema this build would have to guess at.
 *
 * ## Mixed time bases are flagged, not hidden
 *
 * The server has already converted every time-valued row to a common unit, so the numbers are
 * comparable. What conversion cannot fix is that the models differ in what they can resolve - a
 * daily variant cannot express a six-hour recovery at all - so the banner stays up even though the
 * arithmetic is sound.
 */
@Component({
  selector: 'app-comparison',
  standalone: true,
  // No FormsModule: the checkboxes and the scenario select are plain `[checked]`/`[value]` with
  // `(change)`, because the store - not a form model - owns the selection.
  imports: [RouterLink, MetricBadgeComponent, MetricRadarComponent],
  templateUrl: './comparison.component.html',
  styleUrl: './comparison.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparisonComponent {
  readonly store = inject(ComparisonStore);
  readonly exports = inject(ResultsExportService);
  private readonly route = inject(ActivatedRoute);

  readonly projectId = toSignal(
    this.route.paramMap.pipe(map((params) => Number(params.get('projectId')) || null)),
    { initialValue: null },
  );

  /**
   * `?runIds=12,13` opens the run-keyed mode of FR-17 - how the editor's "compare side by side"
   * lands here. Read once per navigation; gestures on the pickers then leave the mode through the
   * store, without rewriting the URL.
   */
  private readonly requestedRunIds = toSignal(
    this.route.queryParamMap.pipe(
      map((params) =>
        (params.get('runIds') ?? '')
          .split(',')
          .map((raw) => Number(raw))
          .filter((id) => Number.isFinite(id) && id > 0),
      ),
    ),
    { initialValue: [] as number[] },
  );

  /** The lever diff of each column, flattened and aligned with `store.variants()`. */
  readonly leverEntries = computed<readonly (readonly LeverEntry[])[]>(() =>
    this.store.variants().map((variant) => flattenLevers(variant.leverChanges)),
  );

  constructor() {
    // `allowSignalWrites` because `ComparisonStore.load` sets its state signals synchronously, which
    // is this effect's whole purpose. Without it Angular 18 refuses the write (`NG0600`) and - since
    // the refusal is raised inside an `async` body - swallows it as an unhandled rejection, leaving
    // the matrix empty with no request on the wire. Identical to the defect fixed on the results
    // dashboard; this is the same link out of the editor's run panel, one button along (FR-17).
    effect(
      () => {
        const projectId = this.projectId();
        if (projectId !== null && this.store.projectId() !== projectId) {
          void this.store.load(projectId, this.requestedRunIds());
        }
      },
      { allowSignalWrites: true },
    );
  }

  /** True in the run-keyed mode of FR-17, which the banner names and offers the exit from. */
  readonly runMode = computed(() => this.store.runIds() !== null);

  // ------------------------------------------------------------------------- rendering

  label(metricCode: string): string {
    return describeMetric(metricCode).label;
  }

  /**
   * A cell, written the way its metric reads.
   *
   * A time-valued row is already in the matrix's common unit, so it is written with that unit rather
   * than through the metric's own `periods` format - the whole point of the conversion is that the
   * row no longer speaks in any one variant's periods.
   */
  cellText(row: ComparisonRow, cell: ComparisonCell | null): string {
    if (!cell) {
      return '-';
    }
    if (row.timeValued && row.unit) {
      return formatInUnit(cell.value, row.unit);
    }
    return formatMetricValue(cell.value, describeMetric(row.metricCode).format);
  }

  /** "14 periods on this network" - the raw figure behind a converted time-valued cell. */
  cellPeriods(cell: ComparisonCell | null): string | null {
    if (!cell || cell.periods === null) {
      return null;
    }
    return `${cell.periods} period${cell.periods === 1 ? '' : 's'}`;
  }

  /** The interval, where the metric has one. */
  cellInterval(row: ComparisonRow, cell: ComparisonCell | null): string | null {
    if (!cell || cell.ciLow === null || cell.ciHigh === null) {
      return null;
    }
    const write = (value: number) =>
      row.timeValued && row.unit
        ? formatInUnit(value, row.unit)
        : formatMetricValue(value, describeMetric(row.metricCode).format);
    return `${write(cell.ciLow)} – ${write(cell.ciHigh)}`;
  }

  /** The arrow beside a row's name: which way is better, from the calculator. */
  directionHint(row: ComparisonRow): string {
    switch (row.direction) {
      case MetricDirection.HIGHER_IS_BETTER:
        return '↑ higher is better';
      case MetricDirection.LOWER_IS_BETTER:
        return '↓ lower is better';
      default:
        return 'not ranked';
    }
  }

  isRanked(row: ComparisonRow): boolean {
    return row.direction !== MetricDirection.NEUTRAL;
  }

  /**
   * The common unit a time-valued row was converted to, as a word - `hours`.
   *
   * Null for a dimensionless row. A method rather than an inline `row.timeValued && row.unit` in the
   * template because `strictTemplates` types an `as` alias from the whole expression, so the inline
   * form would not narrow `unit` away from null.
   */
  rowUnitLabel(row: ComparisonRow): string | null {
    return row.timeValued && row.unit ? formatInUnit(2, row.unit).replace(/^2 /, '') : null;
  }

  /**
   * A column's heading. The run-keyed mode can seat two runs of one network side by side - its
   * whole point (FR-17) - so there the label carries what distinguishes them: the scenario, or
   * "baseline" for a run that applied none.
   */
  columnLabel(variant: ComparisonVariant): string {
    const base = `${variant.name} v${variant.version}`;
    if (this.store.runIds() === null) {
      return base;
    }
    return `${base} - ${variant.scenarioName ?? 'baseline'}`;
  }

  /** "1 day" - a column's clock, shown when the comparison spans more than one. */
  clockOf(variant: ComparisonVariant): string {
    return formatUnits(variant.periodLength);
  }

  /** The run behind a column, or null for a variant that has never been simulated. */
  runLink(variant: ComparisonVariant): (string | number)[] | null {
    return variant.runId === null
      ? null
      : ['/projects', this.projectId() ?? 0, 'simulations', variant.runId];
  }

  // --------------------------------------------------------------------------- actions

  toggle(networkId: Id): void {
    this.store.toggle(networkId);
  }

  setScenario(value: string): void {
    this.store.setScenario(value ? Number(value) : null);
  }

  export(format: TabularExportFormat): void {
    const projectId = this.projectId();
    if (projectId === null) {
      return;
    }
    // The file is "what I am looking at, saved", so the export follows the mode the view is in.
    const runIds = this.store.runIds();
    if (runIds !== null) {
      void this.exports.downloadRunComparison(projectId, runIds, format);
      return;
    }
    void this.exports.downloadComparison(
      projectId,
      this.store.selected(),
      this.store.scenarioId(),
      format,
    );
  }

  leaveRunMode(): void {
    this.store.leaveRunMode();
  }
}
