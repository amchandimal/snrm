package com.snrm.simulation;

import com.snrm.common.ConflictException;

import java.util.List;

/**
 * A run cannot be deleted while a job still owns it (FR-20).
 *
 * <p>A 409, not a 422: the request is well formed, the run is the caller's, and the same request
 * succeeds the moment the job settles — the {@link ConflictException} shape exactly, and the same
 * reading {@link com.snrm.metrics.RunNotComparableException} takes of a run that is not yet
 * {@code DONE}. The remedy is named in the message because it is an endpoint the caller has to
 * call first: {@code DELETE /jobs/{jobId}}.
 *
 * <p><strong>Why not simply cancel the job here.</strong> Cancellation is cooperative —
 * it raises a flag the replication loop observes and never interrupts a worker, so "cancel then
 * delete" is not one action the server can perform inside one request without either waiting on a
 * Monte Carlo run or deleting a row a worker is still writing against. Two calls, and the caller
 * decides between them, is the honest shape.
 *
 * <h2>The whole-network form refuses whole</h2>
 *
 * <p>{@code DELETE /networks/{id}/runs} does not skip the active runs and delete the rest. A partial
 * discard is worse than either whole answer: the network would stay frozen (the active run still
 * locks it), so the researcher would have destroyed every completed result and gained
 * nothing — and the one thing they asked for, an editable network, would be the one thing they did
 * not get. Refusing names the runs in the way, and a second attempt after cancelling does the whole
 * job.
 */
public class RunActiveException extends ConflictException {

    /** The {@code code} member of the problem document. */
    public static final String CODE = "RUN_ACTIVE";

    /** One run, addressed directly — {@code DELETE /simulations/{runId}}. */
    public RunActiveException(long runId, SimulationStatus status) {
        super(("Run %d is %s, so a job still owns it and it cannot be deleted. Cancel the job "
                + "first — DELETE /jobs/{jobId} — and delete the run once it reports CANCELLED "
                + "(FR-20).")
                .formatted(runId, status));
    }

    /** The whole-network form — {@code DELETE /networks/{id}/runs}. */
    public RunActiveException(long networkId, List<SimulationRun> activeRuns) {
        super(("Network %d has %d run(s) still QUEUED or RUNNING (%s), so its runs cannot be "
                + "discarded. Nothing was deleted: a partial discard would destroy the completed "
                + "results and leave the network frozen anyway, since an active run locks it too. "
                + "Cancel the job(s) with DELETE /jobs/{jobId} and try again (FR-20).")
                .formatted(networkId, activeRuns.size(), describe(activeRuns)));
    }

    @Override
    public String code() {
        return CODE;
    }

    private static String describe(List<SimulationRun> runs) {
        return runs.stream()
                .map(run -> "run %d is %s".formatted(run.getId(), run.getStatus()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }
}
