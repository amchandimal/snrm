import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import { Subscription, firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { JobPollingService } from '../../core/job-polling.service';
import {
  DisruptionScenario,
  Id,
  Job,
  JobStatus,
  Network,
  SimulationAccepted,
  SimulationParamsRequest,
  SimulationRequest,
  isTerminalJobStatus,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';

/** Where the launcher stands. Mirrors the `LoadState` of the other feature stores. */
export type LauncherState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The run launcher: pick a network, a scenario and parameters, submit, and follow the job
 * (FR-06).
 *
 * ## Everything asynchronous goes through `JobPollingService`
 *
 * This is that service's first caller. The store submits, hands the returned `jobId` to
 * {@link JobPollingService.poll}, and renders what comes back; it implements no timer of its own,
 * which is what will let the Phase 2 configuration search reuse the same loop without a second
 * implementation of "poll until done".
 *
 * ## The submission is validated before it is accepted
 *
 * `POST /simulations` answers 202 only after checking the whole request - the network and scenario
 * are the caller's and in one project, every event's window fits *this* network's horizon, every
 * event resolves to something this network has, and the network has demand to serve. So a 4xx here
 * is a sentence the researcher can act on, and a `FAILED` poll ten seconds later is not the normal
 * way to learn that a scenario named a node a variant does not have. The store surfaces the problem
 * document verbatim rather than a generic toast.
 *
 * ## Submitting freezes the network
 *
 * From the moment the 202 returns, the chosen network is immutable: editing it is refused
 * with `NETWORK_IMMUTABLE` and must fork a variant. Cancelling releases it again - a `CANCELLED` run
 * locks nothing. The form says so before the button is pressed, because "why can I no longer edit
 * this network" is otherwise a mystery discovered several screens later.
 */
@Injectable({ providedIn: 'root' })
export class RunLauncherStore implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly jobs = inject(JobPollingService);

  private readonly _projectId = signal<Id | null>(null);
  private readonly _networks = signal<readonly Network[]>([]);
  private readonly _scenarios = signal<readonly DisruptionScenario[]>([]);
  private readonly _state = signal<LauncherState>('idle');
  private readonly _error = signal<string | null>(null);

  private readonly _submitting = signal(false);
  private readonly _accepted = signal<SimulationAccepted | null>(null);
  private readonly _job = signal<Job | null>(null);
  private readonly _cancelling = signal(false);

  readonly projectId = this._projectId.asReadonly();
  readonly networks = this._networks.asReadonly();
  readonly scenarios = this._scenarios.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();

  readonly submitting = this._submitting.asReadonly();
  /** The 202 body: the job to watch, the run its answer will appear at, and the resolved params. */
  readonly accepted = this._accepted.asReadonly();
  readonly job = this._job.asReadonly();
  readonly cancelling = this._cancelling.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');

  /** True from submission until the job reaches a terminal state - the progress bar's lifetime. */
  readonly running = computed(() => {
    const job = this._job();
    return this._accepted() !== null && (job === null || !isTerminalJobStatus(job.status));
  });

  /** Completion as a whole percentage, for the bar's width and its label. */
  readonly progressPercent = computed(() => {
    const job = this._job();
    if (!job) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(job.progress * 100)));
  });

  /**
   * The run to open once the job succeeds.
   *
   * Read from the 202 rather than from the finished job, because it is known at submission - which
   * is the whole reason `runId` is on that response. Null until a job finishes as DONE, so
   * a cancelled or failed run never navigates anywhere.
   */
  readonly completedRunId = computed<Id | null>(() => {
    const job = this._job();
    const accepted = this._accepted();
    return job?.status === JobStatus.DONE && accepted ? accepted.runId : null;
  });

  /** The chosen network's own record, for the immutability warning and the period unit. */
  networkById(networkId: Id | null): Network | undefined {
    return networkId === null
      ? undefined
      : this._networks().find((network) => network.id === networkId);
  }

  scenarioById(scenarioId: Id | null): DisruptionScenario | undefined {
    return scenarioId === null
      ? undefined
      : this._scenarios().find((scenario) => scenario.id === scenarioId);
  }

  private poll: Subscription | null = null;

  dismissError(): void {
    this._error.set(null);
  }

  /** Loads the two pickers. A run needs a network and a scenario, and both are project-scoped. */
  load(projectId: Id): void {
    this._projectId.set(projectId);
    this._state.set('loading');
    this._error.set(null);
    Promise.all([
      firstValueFrom(this.api.get<Network[]>(`/projects/${projectId}/networks`)),
      firstValueFrom(this.api.get<DisruptionScenario[]>(`/projects/${projectId}/scenarios`)),
    ])
      .then(([networks, scenarios]) => {
        this._networks.set(networks);
        this._scenarios.set(scenarios);
        this._state.set('ready');
      })
      .catch((failure: unknown) => {
        this._error.set(
          problemMessage(failure, 'Could not load the networks and scenarios of this project.'),
        );
        this._state.set('error');
      });
  }

  /**
   * Submits the run and starts following its job.
   *
   * The poll subscription is held on the store rather than tied to a component's lifetime, so
   * navigating away from the launcher does not silently stop watching a job that is still running.
   * {@link reset} and {@link ngOnDestroy} are the only things that end it.
   */
  async submit(
    networkId: Id,
    scenarioId: Id | null,
    params: SimulationParamsRequest,
  ): Promise<SimulationAccepted | null> {
    this.stopPolling();
    this._submitting.set(true);
    this._error.set(null);
    this._job.set(null);
    this._accepted.set(null);

    // A null scenario is the baseline run of FR-17 - the field is omitted from the body rather
    // than sent as null, matching how every other optional field travels.
    const request: SimulationRequest =
      scenarioId === null ? { networkId, params } : { networkId, scenarioId, params };
    try {
      const accepted = await firstValueFrom(
        this.api.post<SimulationAccepted>('/simulations', request),
      );
      this._accepted.set(accepted);
      this.follow(accepted.jobId);
      return accepted;
    } catch (failure: unknown) {
      // The server checked everything before answering, so this message names the actual problem -
      // an event past the horizon, a target this network does not have, a network with no demand.
      this._error.set(problemMessage(failure, 'Could not submit the simulation.'));
      return null;
    } finally {
      this._submitting.set(false);
    }
  }

  /**
   * Asks the job to stop.
   *
   * Cooperative and never immediate: a running job stops at its next safe point - between
   * replications and between periods - because a worker killed between writing a run's metric rows
   * and writing its status would leave a half-persisted result that looks complete. The button
   * therefore reports "stopping" rather than switching straight to stopped, and the poll keeps
   * running until the job actually reports `CANCELLED`.
   *
   * Idempotent server-side: cancelling a job that has already finished is a 200, not an error, so
   * the button does not have to win a race to be correct.
   */
  async cancel(): Promise<void> {
    const accepted = this._accepted();
    if (!accepted || this._cancelling()) {
      return;
    }
    this._cancelling.set(true);
    try {
      await firstValueFrom(this.jobs.cancel(accepted.jobId));
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not cancel the run.'));
    } finally {
      this._cancelling.set(false);
    }
  }

  /** Clears the last submission so the form is usable again. */
  reset(): void {
    this.stopPolling();
    this._accepted.set(null);
    this._job.set(null);
    this._error.set(null);
    this._cancelling.set(false);
  }

  private follow(jobId: string): void {
    this.poll = this.jobs.poll(jobId).subscribe({
      next: (job) => this._job.set(job),
      error: (failure: unknown) => {
        // A job evicted from the server's in-memory retention window answers 404, and the work is
        // not lost: the run and its metrics are durable. Say so, and leave `accepted` in
        // place so the view can still offer the link to the run.
        this._error.set(
          problemMessage(
            failure,
            'Lost track of the job. The run itself is unaffected - open it to see where it got to.',
          ),
        );
      },
    });
  }

  private stopPolling(): void {
    this.poll?.unsubscribe();
    this.poll = null;
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }
}
