import { Id, IsoInstant } from './common.model';

/**
 * Asynchronous job DTOs - `GET /jobs/{jobId}` and `DELETE /jobs/{jobId}`.
 *
 * One job framework serves every long computation: simulations now, Phase 2 configuration searches
 * later. `JobPollingService` is the only thing in the SPA that reads these.
 *
 * Mirrors the backend `JobStatusDto`. The contract is three members - status, progress, message -
 * and the rest are there because a poller needs them and would otherwise derive them badly.
 */

/** Job lifecycle states. The backend `JobStatus` and `SimulationStatus` share them. */
export const JobStatus = {
  /** Validated and queued; no work has started. */
  QUEUED: 'QUEUED',
  /** Executing. `progress` advances. */
  RUNNING: 'RUNNING',
  /** Finished; results are persisted and final. */
  DONE: 'DONE',
  /** Aborted by an error; any partial results are not authoritative. */
  FAILED: 'FAILED',
  /** Stopped by cooperative cancellation. */
  CANCELLED: 'CANCELLED',
} as const;

export type JobStatus = (typeof JobStatus)[keyof typeof JobStatus];

/** States a job never leaves - the stop condition of the poll-until-done loop. */
export const TERMINAL_JOB_STATUSES: readonly JobStatus[] = [
  JobStatus.DONE,
  JobStatus.FAILED,
  JobStatus.CANCELLED,
];

/** True once the job will not change again. */
export function isTerminalJobStatus(status: JobStatus): boolean {
  return TERMINAL_JOB_STATUSES.includes(status);
}

/**
 * What a running simulation knows so far - the `partial` object on the job poll (FR-17).
 *
 * Streaming statistics over the completed replications, and deliberately nothing more: no
 * confidence intervals (an interval over k of N replications describes a sample nobody asked
 * about), and nothing that needs the whole replication set - `CVAR_COST` above all, a functional of
 * the set rather than a mean.
 *
 * **Provisional by definition.** On completion the persisted suite is authoritative and the server
 * discards these figures - the poll carries them only while `status` is RUNNING. A client must
 * label them provisional on screen and must never persist or export them: presenting them as
 * results would invite a conclusion drawn from twelve replications of a hundred.
 */
export interface ProvisionalFigures {
  /** Replications completed so far, across both sets. */
  readonly replicationsDone: number;
  /** Replications the job will execute - 2N paired, N for a baseline run (FR-17). */
  readonly replicationsTotal: number;
  /** Running mean fill rate over completed disrupted replications; null before the first lands. */
  readonly fillRate: number | null;
  /** Running mean worst-period fill rate; null until a demand-carrying replication completes. */
  readonly minFillRate: number | null;
  /** Running mean total cost; null before the first disrupted replication lands. */
  readonly totalCost: number | null;
}

/**
 * Progress report for one job.
 *
 * Job state lives in memory on the server, is bounded by a retention window, and does not survive a
 * restart - so a 404 from `GET /jobs/{id}` does not mean the work was lost. A completed run and its
 * metrics are durable, and the caller was handed {@link Job.resourceId} at submission
 * precisely so it can go and read them.
 */
export interface Job {
  readonly jobId: string;
  /** What kind of job - `SIMULATION` today, a configuration search in Phase 2. */
  readonly type: string;
  readonly status: JobStatus;
  /** Completion in [0,1]. */
  readonly progress: number;
  /** Human-readable note on the current phase, e.g. `replication 137/200`. */
  readonly message?: string;
  /**
   * The row the job is producing - a `SimulationRun` id for simulations.
   *
   * Set from submission, before any result exists, which is what lets the results view open on a
   * run that is still executing and poll the job beside it.
   */
  readonly resourceId?: Id;
  readonly submittedAt?: IsoInstant;
  readonly startedAt?: IsoInstant;
  readonly finishedAt?: IsoInstant;
  /** Why it failed, when `status` is FAILED. The message only - a stack trace stays server-side. */
  readonly error?: string;
  /**
   * The provisional figures of FR-17, while the job runs; absent otherwise.
   *
   * Typed as the simulation's shape because that is the one job type the SPA submits today; the
   * transport is payload-agnostic on the server, and a Phase 2 search job will carry a shape of its
   * own - widen this to a union then, not before.
   */
  readonly partial?: ProvisionalFigures | null;
}

/**
 * The minimum a submission answers with: somewhere to poll.
 *
 * Deliberately narrower than any one submission response - `POST /simulations` returns a
 * `SimulationAccepted` carrying the run id and the resolved parameters too. This is the shape
 * `JobPollingService.submitAndAwait` needs, and therefore the shape Phase 2's search submission will
 * also satisfy, which is what keeps one polling loop serving both.
 */
export interface JobSubmission {
  readonly jobId: string;
}
