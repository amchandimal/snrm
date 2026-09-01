package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The 202 answer to {@code POST /api/v1/simulations}.
 *
 * <blockquote>{@code POST /api/v1/simulations -> 202 Accepted {jobId} (validated, queued)}
 * </blockquote>
 *
 * <p>{@link #runId} is here alongside {@code jobId} because the run row exists by the
 * time this is returned — it has to, since creating it is what freezes the network —
 * and a client that knows where the answer will appear can open the results view on a run
 * that is still executing. Without it, the only way to find the run would be to poll the job until
 * it finished and then list the network's runs, which is two round trips to learn something already
 * decided.
 *
 * <p>{@link #params} is the <em>resolved</em> set, not the requested one — every default applied and
 * the seed that was actually drawn. It is the same object stored in
 * {@code simulation_run.params_json}, so a caller can record the exact instruction that produced its
 * results without a second fetch.
 *
 * @param jobId      poll it at {@code GET /jobs/{jobId}}, cancel at {@code DELETE /jobs/{jobId}}
 * @param runId      the {@code simulation_run.id} whose results will appear at
 *                   {@code GET /simulations/{runId}}
 * @param status     the run's status at the moment of acceptance — always {@code QUEUED}
 * @param params     what the run will actually use
 * @param replications total replications the job will execute: {@code 2 ×} the disrupted count when
 *                   the run includes the paired undisrupted set, and exactly {@code N}
 *                   for a baseline run, which has no disruption to pair against (FR-17)
 */
@Schema(name = "SimulationAccepted",
        description = "A queued simulation job. Poll `jobId`; read results at `runId`.")
public record SimulationAcceptedDto(

        @Schema(description = "Job to poll and cancel.",
                example = "9f1c0b3a-1d2e-4f5a-8b7c-6d5e4f3a2b1c")
        String jobId,

        @Schema(description = "The run this job is producing. Exists already — creating it is what "
                + "freezes the network — so the results view can open on it immediately.",
                example = "12")
        Long runId,

        @Schema(description = "Always QUEUED at this point.", example = "QUEUED")
        SimulationStatus status,

        @Schema(description = "The fully resolved parameter set, including the seed actually drawn. "
                + "The same object stored in simulation_run.params_json.")
        SimulationParams params,

        @Schema(description = "Replications the job will execute — twice the disrupted count when "
                + "the run includes the paired undisrupted set, and exactly N for a "
                + "baseline run (no scenario), which has nothing to pair against (FR-17).",
                example = "200")
        int replications) {
}
