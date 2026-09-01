import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, tap, throwError } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { Id, Project, ProjectRequest } from '../../core/models';
import { problemMessage } from '../../core/problem-details';

/** Lifecycle of the list, kept explicit so the view can tell "empty" from "not loaded yet". */
export type LoadState = 'idle' | 'loading' | 'ready' | 'error';

/** Which row currently has a request in flight; `'new'` is the create form. */
export type PendingKey = Id | 'new' | null;

/**
 * Signal-based state for the projects feature (feature-scoped services, no NgRx).
 *
 * The store owns the list and the last error message; components own transient UI state such as
 * which row is being renamed. Mutating methods return the request so a caller can close a dialog on
 * success, but they do not require it - the signals are already updated by the time it emits.
 *
 * All HTTP goes through `ApiService`, never `HttpClient` directly.
 */
@Injectable({ providedIn: 'root' })
export class ProjectsStore {
  private readonly api = inject(ApiService);

  private readonly _projects = signal<readonly Project[]>([]);
  private readonly _state = signal<LoadState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _pending = signal<PendingKey>(null);

  readonly projects = this._projects.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  readonly pending = this._pending.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');
  readonly isEmpty = computed(() => this._state() === 'ready' && this._projects().length === 0);

  /** Clears the error banner without touching the data. */
  dismissError(): void {
    this._error.set(null);
  }

  /** `GET /projects`. Re-reads unless the list is already loaded and `force` is false. */
  load(force = true): void {
    if (!force && this._state() === 'ready') {
      return;
    }
    this._state.set('loading');
    this.api.get<Project[]>('/projects').subscribe({
      next: (projects) => {
        this._projects.set(sortByName(projects));
        this._error.set(null);
        this._state.set('ready');
      },
      error: (failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not load projects.'));
        this._state.set('error');
      },
    });
  }

  /** `GET /projects/{id}`. Also refreshes the cached row, so the list stays consistent. */
  get(projectId: Id): Observable<Project> {
    return this.api.get<Project>(`/projects/${projectId}`).pipe(tap((project) => this.upsert(project)));
  }

  /** `POST /projects` - 409 `DUPLICATE_NAME` when this user already has that name. */
  create(name: string): Observable<Project> {
    const request: ProjectRequest = { name: name.trim() };
    this._pending.set('new');
    return this.api.post<Project>('/projects', request).pipe(
      tap((project) => {
        this.upsert(project);
        this._error.set(null);
        this._state.set('ready');
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not create the project.'));
        return throwError(() => failure);
      }),
      finalize(() => this._pending.set(null)),
    );
  }

  /** `PUT /projects/{id}` - a full replacement of the client-supplied fields, which is just the name. */
  rename(projectId: Id, name: string): Observable<Project> {
    const request: ProjectRequest = { name: name.trim() };
    this._pending.set(projectId);
    return this.api.put<Project>(`/projects/${projectId}`, request).pipe(
      tap((project) => {
        this.upsert(project);
        this._error.set(null);
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not rename the project.'));
        return throwError(() => failure);
      }),
      finalize(() => this._pending.set(null)),
    );
  }

  /**
   * `DELETE /projects/{id}`.
   *
   * Cascades to everything in the project - networks, nodes, links, products, scenarios, runs,
   * metric results and time series. Unlike editing a network this is not blocked by completed runs:
   * a network is frozen so results stay interpretable beside their inputs, and removing both
   * together leaves nothing to misinterpret.
   */
  remove(projectId: Id): Observable<void> {
    this._pending.set(projectId);
    return this.api.delete<void>(`/projects/${projectId}`).pipe(
      tap(() => {
        this._projects.update((list) => list.filter((project) => project.id !== projectId));
        this._error.set(null);
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not delete the project.'));
        return throwError(() => failure);
      }),
      finalize(() => this._pending.set(null)),
    );
  }

  private upsert(project: Project): void {
    this._projects.update((list) => {
      const without = list.filter((existing) => existing.id !== project.id);
      return sortByName([...without, project]);
    });
  }
}

/** The backend lists projects ordered by name; keep local mutations in the same order. */
function sortByName(projects: readonly Project[]): readonly Project[] {
  return [...projects].sort((a, b) => a.name.localeCompare(b.name));
}
