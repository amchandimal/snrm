package com.snrm.common;

import java.time.Instant;

/**
 * A consistent read of one job's state — the payload of {@code GET /api/v1/jobs/{jobId}}.
 *
 * <blockquote><pre>
 * GET /api/v1/jobs/{jobId} -&gt; {status: QUEUED|RUNNING|DONE|FAILED|CANCELLED,
 *                              progress: 0..1, message}
 * </pre></blockquote>
 *
 * <p>A record rather than a live view of the mutable job record, because a poller reading four
 * volatile fields one at a time could see a {@code DONE} status beside a progress of 0.6. The
 * snapshot is taken under the job's own lock and is internally consistent by construction.
 *
 * @param jobId       the identifier {@code DELETE /jobs/{jobId}} also takes
 * @param type        what kind of job, e.g. {@code SIMULATION}
 * @param status      one of the five states
 * @param progress    completed fraction in {@code [0,1]}; 0 while queued, 1 once done
 * @param message     the phase the job last reported, e.g. {@code "replication 137/200"}
 * @param resourceId  the row being produced — a {@code simulation_run.id} — or null
 * @param submittedAt when the job was accepted
 * @param startedAt   when a worker picked it up, or null while queued
 * @param finishedAt  when it reached a terminal state, or null
 * @param error       the failure message when {@link JobStatus#FAILED}, otherwise null. The message
 *                    only — a stack trace belongs in the log, and {@code server.error.*} in
 *                    {@code application.properties} says internals never leave the process
 * @param partial     what the task last published through {@link ProgressSink#partial} — the
 *                    provisional figures (FR-17) — or null. Present only while the job
 *                    runs: a terminal state discards it, because from then the persisted result is
 *                    authoritative
 */
public record JobSnapshot(
        String jobId,
        String type,
        JobStatus status,
        double progress,
        String message,
        Long resourceId,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        String error,
        Object partial) {
}
