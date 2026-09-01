package com.snrm.network;

import com.snrm.common.ConflictException;

/**
 * A second network marked as a project's baseline.
 *
 * <p>{@code is_baseline} is a plain flag in the model and MySQL has no partial unique index
 * to say "at most one true per project", so the invariant is enforced here — as
 * {@link NetworkRepository#findByProjectIdAndBaselineIsTrue} anticipates.
 *
 * <p>It matters because the flag is what the comparison view names as the configuration every
 * variant is read against, and what the archive records as such. Two baselines
 * would make that label depend on which row a query happened to return first.
 *
 * <p><strong>Two senses of "baseline", kept apart.</strong> This flag is <em>not</em> what
 * {@code DISRUPTION_COST_DELTA}, {@code LOSS_AREA}, {@code TTR} and {@code RESILIENCE_INDEX} are
 * computed against: those read the paired <em>undisrupted replication set</em> of the same run
 * ({@code MetricContext.traces().baseline()}), which is a property of the run and has
 * nothing to do with which network carries this column. The distinction is load-bearing for FR-29 —
 * the flag is an input to no metric, which is half the reason a frozen network may still be
 * renamed and re-flagged ({@link NetworkService#update}).
 *
 * <p>409, with the incumbent's id, so a client can offer to move the flag rather than just refuse.
 * Moving it is two requests — this exception is raised rather than the incumbent being cleared,
 * because clearing another network's flag is not what the caller asked for.
 */
public class MultipleBaselineException extends ConflictException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "BASELINE_ALREADY_SET";

    private final long currentBaselineNetworkId;

    public MultipleBaselineException(long projectId, long currentBaselineNetworkId) {
        super(("Project %d already has network %d as its baseline. Clear that flag first — the "
                + "comparison view measures every variant against exactly one baseline.")
                .formatted(projectId, currentBaselineNetworkId));
        this.currentBaselineNetworkId = currentBaselineNetworkId;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The network currently holding the flag. */
    public long getCurrentBaselineNetworkId() {
        return currentBaselineNetworkId;
    }
}
