package com.snrm.common;

/**
 * A unit of work {@link JobService} will run asynchronously.
 *
 * <p>Deliberately generic. This interface knows nothing about simulations, networks or search: it is
 * a computation that takes a {@link ProgressSink} and produces a result, which is the whole of what
 * the job framework needs in order to be shared by the Monte Carlo runner and the Phase 2
 * configuration search.
 *
 * <p><strong>Checked exceptions are allowed</strong>, unlike {@link Runnable}. A task that fails
 * does so for a reason the caller of {@code GET /jobs/{id}} is entitled to see, and forcing every
 * implementation to wrap its own failures in a {@code RuntimeException} would only move the
 * unwrapping into {@link JobService}. A thrown exception becomes {@link JobStatus#FAILED} with its
 * message on the snapshot; a thrown {@link JobCancelledException} becomes
 * {@link JobStatus#CANCELLED} instead, because a job that stopped when it was asked to did not fail.
 *
 * <p><strong>The result is not returned to the poller.</strong> {@code GET /jobs/{id}} answers with
 * status and progress only; results are fetched from the resource the job produced —
 * {@code GET /simulations/{runId}} — which is why {@link JobService#submit} takes a
 * {@code resourceId} to carry on the snapshot. A task is therefore expected to have persisted
 * whatever it produced before it returns, and its return value exists for tests and for the
 * synchronous callers.
 *
 * @param <R> what the computation produces
 */
@FunctionalInterface
public interface JobTask<R> {

    /**
     * Runs the computation.
     *
     * @param progress where to report to, and where to ask whether to stop; never null
     * @throws JobCancelledException if the task observed {@link ProgressSink#cancelled()} and stopped
     * @throws Exception             any failure; it is recorded on the job and logged
     */
    R run(ProgressSink progress) throws Exception;
}
