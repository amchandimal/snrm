import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { MetricCard, toMetricCard } from '../../../core/metric-display';
import { Id, JobStatus, MetricScope, SimulationRun } from '../../../core/models';
import { DiscardConfirm, discardRunConfirm } from '../../../core/run-discard';
import { CiValueComponent } from '../../../shared/ci-value/ci-value.component';
import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { MetricBadgeComponent } from '../../../shared/metric-badge/metric-badge.component';
import { PerformanceCurveComponent } from '../../simulations/performance-curve/performance-curve.component';
import { DisruptionsStore } from '../disruptions.store';
import { EditorRunStore } from '../editor-run.store';
import { NetworkEditorStore } from '../network-editor.store';
import { ComparisonCandidate, RunTile, comparisonCandidates, toRunTiles } from '../run-history';

/**
 * The run panel (FR-17, FR-21).
 *
 * > "A configuration is judged where it is built."
 *
 * **Before**: two buttons - run the baseline, or run a scenario - with the freeze warning stated
 * before either is pressed, as the standalone launcher states it. **During**: the progress bar, a
 * cancel button, and the provisional figures of FR-17, marked provisional on screen because they
 * are a partial sample the persisted suite will replace. **After**: the run's report in place - the
 * performance curve and the metric cards, rendered by the same components the dashboard uses, so
 * the editor cannot draw a different triangle from the same run - plus a link to the full
 * dashboard, and the `?runIds=` comparison.
 *
 * Under the buttons and above all of that sits the **history** (FR-21): every run of this network,
 * newest first, not only the session's. A `DONE` tile loads its report in place and arms playback;
 * any other tile selects to its status, because there is nothing else it could honestly show. The
 * list is `NetworkEditorStore.runs()` - the same list the FR-20 discard dialog counts - and this
 * component only renders it, through the pure `run-history.ts`.
 *
 * The parameter surface is deliberately thin: replications, seed, noise and the rest belong to the
 * scenario and to the standalone launcher's override form.
 */
@Component({
  selector: 'app-run-panel',
  standalone: true,
  imports: [
    DatePipe,
    DecimalPipe,
    RouterLink,
    CiValueComponent,
    ConfirmDialogComponent,
    MetricBadgeComponent,
    PerformanceCurveComponent,
  ],
  templateUrl: './run-panel.component.html',
  styleUrl: './run-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunPanelComponent {
  readonly store = inject(EditorRunStore);
  readonly editor = inject(NetworkEditorStore);
  private readonly disruptions = inject(DisruptionsStore);

  /** The picker's own choice, or null to follow the disruptions panel's open scenario. */
  private readonly pickedScenarioId = signal<Id | null>(null);

  /**
   * The scenario "Run scenario" would apply.
   *
   * Defaults to whatever the disruptions panel has open - the scenario just authored on the canvas
   * is the one most worth running - and the picker overrides it for this panel only.
   */
  readonly scenarioId = computed<Id | null>(() => {
    const picked = this.pickedScenarioId();
    if (picked !== null) {
      return picked;
    }
    return this.disruptions.scenario()?.id ?? null;
  });

  readonly scenarioIdText = computed(() => this.scenarioId()?.toString() ?? '');

  readonly scenarioOptions = computed(() => this.store.scenarios.scenarios());

  readonly network = computed(() => this.editor.network());

  /** Why nothing can be run right now, or null. */
  readonly blocker = computed<string | null>(() => {
    if (!this.network()) {
      return 'The network is still loading.';
    }
    if (this.store.submitting() || this.store.running()) {
      return 'A run is already in flight.';
    }
    return null;
  });

  readonly canRunScenario = computed(() => this.blocker() === null && this.scenarioId() !== null);

  // ------------------------------------------------------------- the run history (FR-21)

  /**
   * Every run of this network as a tile, newest first - the panel's opening state.
   *
   * Derived from the editor store's list rather than held here, so the history and the FR-20
   * discard confirmation are the same rows: a dialog that has just counted four runs and a panel
   * that shows five would be two readings of one endpoint.
   */
  readonly tiles = computed<readonly RunTile[]>(() => toRunTiles(this.editor.runs()));

  /** The run the tile list is showing as selected - reported or not (FR-21). */
  readonly selectedRunId = computed<Id | null>(
    () => this.store.selectedRun()?.id ?? this.store.reportRun()?.id ?? null,
  );

  /**
   * The selected tile's run record, when it has no report to show (FR-21).
   *
   * `QUEUED`, `RUNNING`, `FAILED` and `CANCELLED` select to their status only. An active one is not
   * followed live and deliberately so: the job id it would take was issued to its submitter alone,
   * so the panel offers **Refresh** rather than growing a second poll loop.
   */
  readonly statusOnlyRun = computed<SimulationRun | null>(() => {
    const selected = this.store.selectedRun();
    return selected !== null && selected.status !== JobStatus.DONE ? selected : null;
  });

  readonly historyEmpty = computed(
    () =>
      this.tiles().length === 0 &&
      !this.editor.loadingRuns() &&
      this.editor.runsError() === null,
  );

  selectTile(tile: RunTile): void {
    const run = this.editor.runs().find((entry) => entry.id === tile.runId);
    if (run) {
      this.store.selectRun(run);
    }
  }

  refreshHistory(): void {
    this.store.refreshHistory();
  }

  // ------------------------------------------------------------------- the report

  readonly reportCards = computed<readonly MetricCard[]>(() => {
    const period = this.store.reportRun()?.periodLength ?? null;
    return this.store
      .reportMetrics()
      .filter((metric) => metric.scope === MetricScope.NETWORK)
      .map((metric) => toMetricCard(metric, period));
  });

  readonly reportPeriodLength = computed(() => this.store.reportRun()?.periodLength ?? null);

  /** The full dashboard of the completed run - the fourth screen, now one click, not four. */
  readonly dashboardLink = computed<(string | number)[] | null>(() => {
    const run = this.store.reportRun();
    const network = this.network();
    return run && network ? ['/projects', network.projectId, 'simulations', run.id] : null;
  });

  /**
   * Which other runs the loaded report can be read beside, from the **history** (FR-17, FR-21).
   *
   * The candidates were the session's completed runs until FR-21; they are the history's `DONE`
   * tiles now, which is the same set during a session that ran two and a strictly larger one
   * afterwards - a baseline from Monday and a disruption from Tuesday are comparable, and no
   * session ever saw both.
   */
  readonly comparisons = computed<readonly ComparisonCandidate[]>(() =>
    comparisonCandidates(this.tiles(), this.store.reportRun()?.id ?? null),
  );

  readonly comparisonLink = computed<string | null>(() => {
    const network = this.network();
    return network ? `/projects/${network.projectId}/comparison` : null;
  });

  // ------------------------------------------------------------ delete a run (FR-20)

  /** The run whose typed confirmation is up, or null. */
  private readonly deleteTarget = signal<SimulationRun | null>(null);

  /**
   * What that confirmation says - the single-run form, from the same specced module the editor's
   * whole-network discard uses, so the two cannot ask different questions about the same act.
   */
  readonly deleteConfirm = computed<DiscardConfirm | null>(() => {
    const run = this.deleteTarget();
    return run === null ? null : discardRunConfirm(run);
  });

  requestDeleteRun(run: SimulationRun | null): void {
    if (run !== null) {
      this.deleteTarget.set(run);
    }
  }

  requestDeleteTile(tile: RunTile): void {
    const run = this.editor.runs().find((entry) => entry.id === tile.runId);
    if (run && tile.deletable) {
      this.deleteTarget.set(run);
    }
  }

  async confirmDeleteRun(): Promise<void> {
    const run = this.deleteTarget();
    if (run === null) {
      return;
    }
    // Closing first keeps the modal from rendering against a run the store is about to drop.
    this.deleteTarget.set(null);
    await this.store.deleteRun(run);
  }

  cancelDelete(): void {
    this.deleteTarget.set(null);
  }

  // ---------------------------------------------------------------------- actions

  onScenarioPicked(raw: string): void {
    const id = Number(raw);
    this.pickedScenarioId.set(Number.isFinite(id) && id > 0 ? id : null);
  }

  runBaseline(): void {
    void this.store.submit(null);
  }

  runScenario(): void {
    const scenarioId = this.scenarioId();
    if (scenarioId !== null) {
      void this.store.submit(scenarioId);
    }
  }

  cancel(): void {
    void this.store.cancel();
  }

  reset(): void {
    this.store.reset();
  }

  percent(fraction: number | null): string {
    return fraction === null ? '-' : `${(fraction * 100).toFixed(1)}%`;
  }
}
