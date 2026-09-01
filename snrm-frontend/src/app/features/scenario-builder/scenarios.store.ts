import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, tap, throwError } from 'rxjs';

import { ApiService } from '../../core/api.service';
import {
  DisruptionScenario,
  DisruptionScenarioRequest,
  DuplicateScenarioRequest,
  Id,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';

/** Where the list stands, mirroring `LoadState` in `features/projects`. */
export type ScenarioListState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The disruption scenarios of one project (FR-05).
 *
 * Holds the **list** - the sidebar of the scenario builder, and the create / duplicate / delete
 * actions on it. The bars of one scenario's timeline live in `ScenarioBuilderStore`, which is a
 * separate store for the same reason `TopologicalMetricsStore` is separate from
 * `NetworkEditorStore`: the list is about the project and stays valid while any one timeline is
 * loading, failing or being edited.
 *
 * Rows carry an `eventCount` rather than their events, which is what the list response sends.
 * When a timeline write changes that count, {@link noteEventCount} keeps the row honest
 * without a reload - the builder knows what it just did, and refetching the whole list to move one
 * number would flicker the sidebar under the user's cursor.
 */
@Injectable({ providedIn: 'root' })
export class ScenariosStore {
  private readonly api = inject(ApiService);

  private readonly _projectId = signal<Id | null>(null);
  private readonly _scenarios = signal<readonly DisruptionScenario[]>([]);
  private readonly _state = signal<ScenarioListState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _creating = signal(false);
  private readonly _busyFor = signal<Id | null>(null);

  readonly scenarios = this._scenarios.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  readonly creating = this._creating.asReadonly();
  /** Which scenario has a duplicate or delete in flight, so its row alone shows progress. */
  readonly busyFor = this._busyFor.asReadonly();

  readonly projectId = this._projectId.asReadonly();
  readonly isLoading = computed(() => this._state() === 'loading');
  readonly isEmpty = computed(() => this._state() === 'ready' && this._scenarios().length === 0);

  dismissError(): void {
    this._error.set(null);
  }

  /** `GET /projects/{id}/scenarios`. */
  load(projectId: Id): void {
    this._projectId.set(projectId);
    this._state.set('loading');
    this._scenarios.set([]);
    this.api.get<DisruptionScenario[]>(`/projects/${projectId}/scenarios`).subscribe({
      next: (scenarios) => {
        this._scenarios.set(sortScenarios(scenarios));
        this._error.set(null);
        this._state.set('ready');
      },
      error: (failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not load the scenarios of this project.'));
        this._state.set('error');
      },
    });
  }

  /** `POST /projects/{id}/scenarios`. A duplicate name comes back 409 `DUPLICATE_NAME`. */
  create(projectId: Id, request: DisruptionScenarioRequest): Observable<DisruptionScenario> {
    this._creating.set(true);
    return this.api.post<DisruptionScenario>(`/projects/${projectId}/scenarios`, request).pipe(
      tap((scenario) => this.upsert(scenario)),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not create the scenario.'));
        return throwError(() => failure);
      }),
      finalize(() => this._creating.set(false)),
    );
  }

  /** `PUT /scenarios/{id}` - the name and the Monte Carlo settings. */
  replace(scenarioId: Id, request: DisruptionScenarioRequest): Observable<DisruptionScenario> {
    this._busyFor.set(scenarioId);
    return this.api.put<DisruptionScenario>(`/scenarios/${scenarioId}`, request).pipe(
      tap((scenario) => this.upsert(scenario)),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not update the scenario.'));
        return throwError(() => failure);
      }),
      finalize(() => this._busyFor.set(null)),
    );
  }

  /**
   * `POST /scenarios/{id}/duplicate` - a deep copy, events included.
   *
   * The body is optional; omitting the name gives `<name> (copy)`. This is the cheap way to explore
   * a variation on a disruption story without editing a scenario a completed run refers to.
   */
  duplicate(scenarioId: Id, request?: DuplicateScenarioRequest): Observable<DisruptionScenario> {
    this._busyFor.set(scenarioId);
    return this.api
      .post<DisruptionScenario>(`/scenarios/${scenarioId}/duplicate`, request ?? null)
      .pipe(
        tap((copy) => this.upsert(copy)),
        catchError((failure: unknown) => {
          this._error.set(problemMessage(failure, 'Could not duplicate the scenario.'));
          return throwError(() => failure);
        }),
        finalize(() => this._busyFor.set(null)),
      );
  }

  /**
   * `DELETE /scenarios/{id}` - the scenario and every event in it.
   *
   * The row goes only once the server has confirmed. An optimistic removal would show the scenario
   * gone in the one case where it certainly is not: a scenario a simulation run still references.
   */
  delete(scenarioId: Id): Observable<void> {
    this._busyFor.set(scenarioId);
    return this.api.delete<void>(`/scenarios/${scenarioId}`).pipe(
      tap(() => {
        this._scenarios.update((list) => list.filter((scenario) => scenario.id !== scenarioId));
        this._error.set(null);
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not delete the scenario.'));
        return throwError(() => failure);
      }),
      finalize(() => this._busyFor.set(null)),
    );
  }

  /**
   * Corrects one row's event count after the timeline has added or removed a bar.
   *
   * Not a refetch: the builder knows exactly what changed, and reloading the list to move a single
   * number would rebuild the sidebar under the user's pointer mid-edit.
   */
  noteEventCount(scenarioId: Id, eventCount: number): void {
    this._scenarios.update((list) =>
      list.map((scenario) =>
        scenario.id === scenarioId ? { ...scenario, eventCount } : scenario,
      ),
    );
  }

  private upsert(scenario: DisruptionScenario): void {
    this._scenarios.update((list) => {
      const without = list.filter((existing) => existing.id !== scenario.id);
      return sortScenarios([...without, scenario]);
    });
    this._error.set(null);
    this._state.set('ready');
  }
}

/** Alphabetical, the order the list endpoint returns and the sidebar reads in. */
function sortScenarios(
  scenarios: readonly DisruptionScenario[],
): readonly DisruptionScenario[] {
  return [...scenarios].sort((a, b) => a.name.localeCompare(b.name));
}
