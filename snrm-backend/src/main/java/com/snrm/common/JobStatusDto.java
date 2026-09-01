package com.snrm.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * {@code GET /api/v1/jobs/{jobId}} as the API returns it.
 *
 * <p>Three members are the core of it — {@code status}, {@code progress} and {@code message}. The
 * rest are here because a poller needs them and would otherwise derive them badly:
 * {@link #resourceId} is where the answer will be, so the client does not have to remember what it
 * submitted; the three timestamps let the UI show elapsed time and a rate without guessing when the
 * job started; and {@link #error} carries a failed job's reason, which is the only thing that turns
 * a {@code FAILED} poll into something a researcher can act on.
 *
 * @param jobId       the identifier {@code DELETE /jobs/{jobId}} also takes
 * @param type        what kind of job, e.g. {@code SIMULATION}
 * @param status      {@code QUEUED | RUNNING | DONE | FAILED | CANCELLED}
 * @param progress    completed fraction in {@code [0,1]}
 * @param message     the phase the job last reported
 * @param resourceId  the row it is producing — a {@code simulation_run.id} — or null
 * @param submittedAt when it was accepted
 * @param startedAt   when a worker picked it up, or null while queued
 * @param finishedAt  when it reached a terminal state, or null
 * @param error       the failure message when {@code FAILED}, otherwise null
 */
@Schema(name = "JobStatus",
        description = "State of an asynchronous job. Poll until `status` is terminal, "
                + "then read the results from the resource named by `resourceId`.")
public record JobStatusDto(

        @Schema(description = "Opaque job identifier.",
                example = "9f1c0b3a-1d2e-4f5a-8b7c-6d5e4f3a2b1c")
        String jobId,

        @Schema(description = "What kind of job this is.", example = "SIMULATION")
        String type,

        @Schema(description = "QUEUED while waiting, RUNNING while executing, then one of DONE, "
                + "FAILED or CANCELLED.", example = "RUNNING")
        JobStatus status,

        @Schema(description = "Completed fraction, 0 to 1.", example = "0.68")
        double progress,

        @Schema(description = "Short human-readable phase.", example = "replication 137/200")
        String message,

        @Schema(description = "The row this job is producing — for a simulation, the "
                + "`simulation_run.id` whose results appear at GET /simulations/{runId}. Available "
                + "from submission, before any result exists.", example = "12")
        Long resourceId,

        @Schema(description = "When the job was accepted.", example = "2026-08-02T09:15:04Z")
        Instant submittedAt,

        @Schema(description = "When a worker picked it up; null while queued.",
                example = "2026-08-02T09:15:04Z")
        Instant startedAt,

        @Schema(description = "When it reached a terminal state; null otherwise.", example = "null")
        Instant finishedAt,

        @Schema(description = "Why it failed, when `status` is FAILED. The message only — a stack "
                + "trace stays in the server log.", example = "null")
        String error,

        @Schema(description = "What the job knows so far — provisional figures published while it "
                + "runs (FR-17). For a simulation: {replicationsDone, replicationsTotal, "
                + "fillRate, minFillRate, totalCost}, streaming statistics over the completed "
                + "replications only — no confidence intervals (an interval over k of N describes "
                + "a sample nobody asked about) and nothing that needs the whole set. Null while "
                + "queued and null again once terminal: the persisted suite then supersedes these "
                + "figures, which a client must label provisional and never persist or export.")
        Object partial) {

    /** The framework's internal form, as the API returns it. */
    public static JobStatusDto of(JobSnapshot snapshot) {
        return new JobStatusDto(snapshot.jobId(), snapshot.type(), snapshot.status(),
                snapshot.progress(), snapshot.message(), snapshot.resourceId(),
                snapshot.submittedAt(), snapshot.startedAt(), snapshot.finishedAt(),
                snapshot.error(), snapshot.partial());
    }
}
