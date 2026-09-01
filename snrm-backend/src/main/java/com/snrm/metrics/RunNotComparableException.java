package com.snrm.metrics;

import com.snrm.common.ConflictException;
import com.snrm.simulation.SimulationStatus;

/**
 * A run named in {@code ?runIds=} is not {@code DONE} (FR-17).
 *
 * <p>A 409, not a 404: the run exists and is the caller's, and the same request will succeed the
 * moment it finishes — the {@link ConflictException} shape exactly. Seating it as an empty column
 * instead was rejected because, unlike a network with no run (whose column shows the topological
 * half, {@code NO_RUN}), a half-finished run has nothing to show <em>yet</em>, and an empty column
 * that will silently fill in on the next request is a matrix that changes meaning between two
 * renders of the same URL.
 */
public class RunNotComparableException extends ConflictException {

    public static final String CODE = "RUN_NOT_DONE";

    public RunNotComparableException(long runId, SimulationStatus status) {
        super(("Run %d is %s, not DONE, so it has no results to compare yet. Poll its job — or "
                + "GET /simulations/%d — and compare once it completes.")
                .formatted(runId, status, runId));
    }

    @Override
    public String code() {
        return CODE;
    }
}
