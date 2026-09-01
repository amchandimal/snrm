package com.snrm.common;

/**
 * Lifecycle of an asynchronous job:
 * {@code QUEUED | RUNNING | DONE | FAILED | CANCELLED}.
 *
 * <p>The same five literals as {@code com.snrm.simulation.SimulationStatus}, and deliberately a
 * separate type rather than a shared one. They answer different questions and have different
 * lifetimes: this one lives in memory and describes <em>the execution</em>, which is forgotten once
 * the process restarts; the other is a column and describes <em>the run</em>, which outlives every
 * process and carries the network freeze. A simulation job that is {@code DONE} and a
 * simulation run that is {@code DONE} coincide in practice, but a job the framework has evicted from
 * its retention window is gone while its run is still readable — so one enum could not be the answer
 * to both.
 *
 * <p>{@link com.snrm.simulation.SimulationService} maps between them in one place.
 */
public enum JobStatus {

    /** Accepted and queued; no worker has picked it up. */
    QUEUED,

    /** A worker is executing the task. */
    RUNNING,

    /** Finished normally; whatever the task produced has been persisted. */
    DONE,

    /** The task threw. {@code JobSnapshot.error()} carries the message. */
    FAILED,

    /** Stopped by cooperative cancellation, or dropped from the queue before it started. */
    CANCELLED;

    /** Whether the job has reached a final state — nothing further will change on it. */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
