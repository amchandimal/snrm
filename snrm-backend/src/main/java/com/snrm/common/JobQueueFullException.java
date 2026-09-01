package com.snrm.common;

/**
 * The bounded job queue refused a submission.
 *
 * <p>The executor behind {@link JobService} is deliberately bounded on both axes — a small number of
 * worker threads and a finite queue — because a simulation job fans out to hundreds of replications
 * on virtual threads and three of them running at once would contend for the machine
 * rather than share it. Min-cost-flow per period becomes slow on large networks × replications,
 * and replication parallelism is the mitigation; the bound is what keeps that parallelism inside
 * one job instead of spread across many.
 *
 * <p>Rendered as <strong>429 Too Many Requests</strong> with a {@code Retry-After} hint, because the
 * request is well-formed and the remedy is to submit it again shortly — which is exactly what that
 * status means, and what a 503 would not tell the caller.
 */
public class JobQueueFullException extends DomainException {

    private final int workers;
    private final int queueCapacity;

    public JobQueueFullException(int workers, int queueCapacity) {
        super(("The job queue is full: %d worker(s) are busy and %d job(s) are already waiting. "
                + "Simulation jobs are bounded deliberately — each one fans out to hundreds of "
                + "replications — so submit again once one of them finishes.")
                .formatted(workers, queueCapacity));
        this.workers = workers;
        this.queueCapacity = queueCapacity;
    }

    @Override
    public String code() {
        return "JOB_QUEUE_FULL";
    }

    public int getWorkers() {
        return workers;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }
}
