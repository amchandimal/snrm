package com.snrm.simulation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a simulation run, mirroring the job states
 * ({@code QUEUED | RUNNING | DONE | FAILED | CANCELLED}).
 *
 * <p>Persisted as a MySQL {@code ENUM} — keep the literals in step with {@code V2__domain.sql}.
 */
public enum SimulationStatus {

    /** Validated and queued; no work has started. */
    QUEUED(true),

    /** Replications are executing against the network snapshot. */
    RUNNING(true),

    /** Finished; metric results and time series are persisted and final. */
    DONE(true),

    /** Aborted by an error; any partial results are not authoritative. */
    FAILED(false),

    /** Stopped by cooperative cancellation. */
    CANCELLED(false);

    private final boolean networkLocking;

    SimulationStatus(boolean networkLocking) {
        this.networkLocking = networkLocking;
    }

    /**
     * Whether a run in this state freezes the network it references.
     *
     * <p>{@link #DONE} is the plain case: its results are only interpretable
     * beside the exact structure that produced them. {@link #RUNNING} and {@link #QUEUED} are
     * included because they are on their way to becoming {@code DONE} against the network as it
     * stands now — editing underneath an accepted run would silently change the inputs of a result
     * that has already been promised to the caller.
     *
     * <p>{@link #FAILED} and {@link #CANCELLED} produce no authoritative results, so they hold
     * nothing frozen.
     *
     * <p>This method is the single definition of the policy; {@link com.snrm.network.NetworkMutationGuard}
     * reads it and nothing else decides.
     */
    public boolean isNetworkLocking() {
        return networkLocking;
    }

    /** The statuses for which {@link #isNetworkLocking()} holds; the guard's query argument. */
    public static Set<SimulationStatus> networkLocking() {
        return NETWORK_LOCKING;
    }

    /**
     * Whether a run in this state is still owned by a job, and so cannot be deleted (FR-20).
     *
     * <p>{@link #QUEUED} and {@link #RUNNING} are the two states in which a worker either holds the
     * run or is about to: deleting the row underneath one would leave {@code SimulationRunWriter}
     * writing a status, a metric suite and three time series against an id that no longer exists,
     * and cancellation is cooperative precisely so a half-persisted result cannot happen.
     * So deletion is <em>refused</em> rather than made to race — {@code DELETE /jobs/{jobId}} first,
     * then delete the run.
     *
     * <p>This is deliberately <strong>not</strong> the same question as
     * {@link #isNetworkLocking()}, and the difference is the whole of FR-20: {@link #DONE} freezes
     * its network and is nevertheless deletable, which is what gives the freeze an exit.
     * {@link #FAILED} and {@link #CANCELLED} lock nothing and are deletable for the ordinary reason.
     */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }

    /** The statuses for which {@link #isActive()} holds. */
    public static Set<SimulationStatus> active() {
        return ACTIVE;
    }

    private static final Set<SimulationStatus> NETWORK_LOCKING;
    private static final Set<SimulationStatus> ACTIVE;

    static {
        EnumSet<SimulationStatus> locking = EnumSet.noneOf(SimulationStatus.class);
        EnumSet<SimulationStatus> active = EnumSet.noneOf(SimulationStatus.class);
        for (SimulationStatus status : values()) {
            if (status.networkLocking) {
                locking.add(status);
            }
            if (status.isActive()) {
                active.add(status);
            }
        }
        NETWORK_LOCKING = Collections.unmodifiableSet(locking);
        ACTIVE = Collections.unmodifiableSet(active);
    }
}
