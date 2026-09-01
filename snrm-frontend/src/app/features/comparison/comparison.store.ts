import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { ComparisonMatrix, DisruptionScenario, Id, Network } from '../../core/models';
import { problemMessage } from '../../core/problem-details';

export type ComparisonState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The comparison view's state (FR-10).
 *
 * > "Comparison view - variants × metrics matrix with best-in-row highlighting, radar chart of
 * > normalised metrics, and lever-change annotations from `lever_changes_json`."
 *
 * ## Almost nothing is computed here
 *
 * The matrix arrives from `GET /projects/{id}/comparison` with the winners already marked, the
 * time-valued rows already converted to a common unit, and the caveats already stated. That is
 * deliberate on the server's side (see `ComparisonService`) and this store keeps it that way: it
 * holds the selection, fetches, and exposes what came back. Re-deciding "which cell wins" in the
 * client would be a second implementation of a question the calculators already answer, and the
 * exported spreadsheet - which the server builds from the same object - would eventually disagree
 * with the screen.
 *
 * The one thing it does add is the radar's normalisation, which is a property of the *chart* rather
 * than of the data: see `radar-geometry.ts`.
 *
 * ## The selection defaults to everything, and that is usually wrong
 *
 * With no `networkIds`, the endpoint compares every network in the project - which is the right
 * default for a first look and the wrong one for a finding, since it mixes configurations evaluated
 * against different scenarios. The view surfaces the server's `MIXED_SCENARIOS` note rather than
 * quietly filtering, and offers the scenario picker as the fix.
 */
@Injectable({ providedIn: 'root' })
export class ComparisonStore {
  private readonly api = inject(ApiService);

  private readonly _projectId = signal<Id | null>(null);
  private readonly _networks = signal<readonly Network[]>([]);
  private readonly _scenarios = signal<readonly DisruptionScenario[]>([]);
  private readonly _selected = signal<readonly Id[]>([]);
  private readonly _scenarioId = signal<Id | null>(null);
  /**
   * Non-null puts the view in the **run-keyed mode** of FR-17: one column per named run, which is
   * the only form that can seat two runs of one network side by side - the editor's baseline beside
   * its disruption run, or one network under two scenarios. Mutually exclusive with the network
   * selection, exactly as the endpoint's two selectors are; any gesture on the network checkboxes
   * or the scenario picker leaves this mode.
   */
  private readonly _runIds = signal<readonly Id[] | null>(null);
  private readonly _matrix = signal<ComparisonMatrix | null>(null);
  private readonly _state = signal<ComparisonState>('idle');
  private readonly _error = signal<string | null>(null);

  readonly projectId = this._projectId.asReadonly();
  readonly networks = this._networks.asReadonly();
  readonly scenarios = this._scenarios.asReadonly();
  /** The chosen columns, in the order they will appear. */
  readonly selected = this._selected.asReadonly();
  readonly scenarioId = this._scenarioId.asReadonly();
  /** The runs keying the columns, or null in the ordinary network-keyed mode. */
  readonly runIds = this._runIds.asReadonly();
  readonly matrix = this._matrix.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');
  readonly variants = computed(() => this._matrix()?.variants ?? []);
  readonly rows = computed(() => this._matrix()?.rows ?? []);
  readonly notes = computed(() => this._matrix()?.notes ?? []);

  /** The mixed-time-bases flag, straight from the server. */
  readonly mixedTimeBases = computed(() => this._matrix()?.mixedTimeBases ?? false);
  readonly mixedScenarios = computed(() => this._matrix()?.mixedScenarios ?? false);
  readonly commonUnit = computed(() => this._matrix()?.timeUnit ?? null);

  /** Columns that carry a lever diff - the annotation strip only appears if any do. */
  readonly hasLeverChanges = computed(() =>
    this.variants().some((variant) => variant.leverChanges !== null),
  );

  dismissError(): void {
    this._error.set(null);
  }

  /**
   * Loads the project's networks and scenarios, then the matrix.
   *
   * The initial selection is every network, matching what the endpoint does with no `networkIds` -
   * so the checkboxes always describe what is on screen, even before anything is ticked.
   *
   * @param runIds open in the run-keyed mode of FR-17 - one column per named run. How the editor's
   *               "compare side by side" arrives, as `?runIds=` on the URL.
   */
  async load(projectId: Id, runIds?: readonly Id[]): Promise<void> {
    this._projectId.set(projectId);
    this._runIds.set(runIds?.length ? [...runIds] : null);
    this._state.set('loading');
    this._error.set(null);
    try {
      const [networks, scenarios] = await Promise.all([
        firstValueFrom(this.api.get<Network[]>(`/projects/${projectId}/networks`)),
        firstValueFrom(this.api.get<DisruptionScenario[]>(`/projects/${projectId}/scenarios`)),
      ]);
      this._networks.set(networks);
      this._scenarios.set(scenarios);
      if (this._selected().length === 0) {
        this._selected.set(networks.map((network) => network.id));
      }
      await this.refresh();
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not load this project.'));
      this._state.set('error');
    }
  }

  /** `GET /projects/{id}/comparison` - `?runIds=` in the run-keyed mode, `?networkIds=` otherwise. */
  async refresh(): Promise<void> {
    const projectId = this._projectId();
    if (projectId === null) {
      return;
    }
    this._state.set('loading');
    this._error.set(null);
    try {
      const runIds = this._runIds();
      const matrix = await firstValueFrom(
        this.api.get<ComparisonMatrix>(`/projects/${projectId}/comparison`, {
          // The two selectors are mutually exclusive on the server (FR-17), so exactly one is sent.
          params: runIds
            ? { runIds: [...runIds] }
            : {
                networkIds: this._selected().length ? [...this._selected()] : undefined,
                scenarioId: this._scenarioId() ?? undefined,
              },
        }),
      );
      this._matrix.set(matrix);
      this._state.set('ready');
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not build the comparison.'));
      this._state.set('error');
    }
  }

  /** Back to the network-keyed view - the banner's exit from the run-keyed mode. */
  leaveRunMode(): void {
    if (this._runIds() !== null) {
      this._runIds.set(null);
      void this.refresh();
    }
  }

  /**
   * Adds or removes a column.
   *
   * Order is preserved on add - a researcher who ticks the baseline first is building a table to
   * read left to right, and the server keeps the order it is given.
   */
  toggle(networkId: Id): void {
    this._runIds.set(null);
    this._selected.update((ids) =>
      ids.includes(networkId) ? ids.filter((id) => id !== networkId) : [...ids, networkId],
    );
    void this.refresh();
  }

  isSelected(networkId: Id): boolean {
    return this._selected().includes(networkId);
  }

  selectAll(): void {
    this._runIds.set(null);
    this._selected.set(this._networks().map((network) => network.id));
    void this.refresh();
  }

  clear(): void {
    this._runIds.set(null);
    this._selected.set([]);
    void this.refresh();
  }

  /** Pins every column to one scenario, which is what makes the comparison about configurations. */
  setScenario(scenarioId: Id | null): void {
    this._runIds.set(null);
    this._scenarioId.set(scenarioId);
    void this.refresh();
  }
}
