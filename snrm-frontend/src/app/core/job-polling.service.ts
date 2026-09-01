import { Injectable, inject } from '@angular/core';
import {
  Observable,
  concatMap,
  distinctUntilChanged,
  last,
  of,
  switchMap,
  takeWhile,
  throwError,
  timeout,
  timer,
} from 'rxjs';

import { ApiService } from './api.service';
import { environment } from '../../environments/environment';
import { Job, JobStatus, JobSubmission, isTerminalJobStatus } from './models';

/** Per-call overrides for the polling cadence. */
export interface PollOptions {
  /** Milliseconds between status requests. Defaults to `environment.jobPollIntervalMs`. */
  readonly intervalMs?: number;
  /** Give up after this long. Defaults to `environment.jobPollTimeoutMs`. */
  readonly timeoutMs?: number;
}

/** Raised when a job ends in FAILED or CANCELLED. Carries the last status for the UI to report. */
export class JobFailedError extends Error {
  constructor(readonly job: Job) {
    super(job.message ?? `Job ${job.jobId} ended as ${job.status}.`);
    this.name = 'JobFailedError';
  }
}

/**
 * The poll-until-done pattern, in one place.
 *
 * SKELETON - wired to the endpoints (`POST` returns `202 {jobId}`, `GET /jobs/{jobId}`
 * reports `{status, progress, message}`, `DELETE /jobs/{jobId}` cancels cooperatively), but the
 * backend has no job controller yet. Stage 9 (simulations) is its first caller; the Phase 2
 * configuration search is the second, because both sit behind the same job framework.
 *
 * No component may implement its own polling loop. Everything here is cold and unsubscribing stops
 * the polling, so an `async` pipe or a `toSignal` in a component ties the loop's lifetime to the
 * view without any teardown code.
 */
@Injectable({ providedIn: 'root' })
export class JobPollingService {
  private readonly api = inject(ApiService);

  /**
   * Emits the job's status every interval until it reaches a terminal state, which is emitted last.
   *
   * `concatMap` rather than `switchMap` over the timer: a slow status call must not have a second
   * one launched underneath it, or progress can appear to move backwards.
   */
  poll(jobId: string, options?: PollOptions): Observable<Job> {
    const intervalMs = options?.intervalMs ?? environment.jobPollIntervalMs;
    const timeoutMs = options?.timeoutMs ?? environment.jobPollTimeoutMs;

    return timer(0, intervalMs).pipe(
      concatMap(() => this.status(jobId)),
      // `true` keeps the terminal status: the caller needs to see how it ended, not just that it did.
      takeWhile((job) => !isTerminalJobStatus(job.status), true),
      // The partial is compared by its replication count rather than by reference: every poll
      // deserialises a fresh object, and reference inequality would defeat the deduplication this
      // operator exists for. Progress alone is not enough since FR-17 - the server throttles the
      // bar to whole steps, and the figures move between them.
      distinctUntilChanged(
        (a, b) =>
          a.status === b.status &&
          a.progress === b.progress &&
          a.partial?.replicationsDone === b.partial?.replicationsDone,
      ),
      timeout({ each: timeoutMs }),
    );
  }

  /** One status read. `GET /jobs/{jobId}`. */
  status(jobId: string): Observable<Job> {
    return this.api.get<Job>(`/jobs/${jobId}`);
  }

  /** Cooperative cancellation. `DELETE /jobs/{jobId}`. */
  cancel(jobId: string): Observable<void> {
    return this.api.delete<void>(`/jobs/${jobId}`);
  }

  /**
   * Submit → poll → settle, as one observable.
   *
   * Pass the submitting call (e.g. `api.post<JobSubmission>('/simulations', request)`); the result
   * is the terminal job, or a {@link JobFailedError} if it did not finish as DONE. Intermediate
   * progress is available by subscribing to {@link poll} separately with the same job id - this
   * method is for callers that only care about the outcome.
   */
  submitAndAwait(submission: Observable<JobSubmission>, options?: PollOptions): Observable<Job> {
    return submission.pipe(
      switchMap(({ jobId }) => this.poll(jobId, options)),
      last(),
      switchMap((job) =>
        job.status === JobStatus.DONE ? of(job) : throwError(() => new JobFailedError(job)),
      ),
    );
  }
}
