package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /api/v1/simulations} ({@code {networkId, scenarioId?, params}}).
 *
 * <p>The network, an optional scenario and an optional parameter object. Nothing else is needed and
 * nothing else is accepted: the horizon comes from the network, the replication count and seed from
 * the scenario unless {@code params} overrides them, and the events from the scenario's own rows.
 *
 * <p><strong>No scenario is the baseline run of FR-17</strong>: the network evaluated
 * with nothing going wrong — the first thing worth running on a network just built or imported, and
 * the comparator every disrupted run is read against. Such a run executes its N replications with no
 * paired undisrupted set, because the pairing exists to isolate a disruption's effect and
 * there is none to isolate; the disruption-relative metrics produce no rows, which is a different
 * thing from zero and is how every reader must treat an absent row.
 *
 * <p><strong>The network and the scenario must belong to the same project.</strong> A scenario is
 * project-scoped so it can be replayed across configuration variants; replaying it
 * against a network from another project would resolve its node and link targets against a structure
 * no run of that scenario should ever use.
 *
 * @param networkId  the network to evaluate
 * @param scenarioId the disruption scenario to evaluate it against, or null for the baseline run
 * @param params     optional overrides; every field of it is optional too
 */
@Schema(name = "SimulationRequest",
        description = "Submits a simulation job. Answered with 202 and a jobId to "
                + "poll, plus the runId whose results will appear at GET /simulations/{runId}. "
                + "Omit scenarioId for an undisrupted baseline run (FR-17).")
public record SimulationRequest(

        @Schema(description = "Network to evaluate. Frozen from the moment the run is accepted, "
                + "so editing it afterwards is refused with NETWORK_IMMUTABLE and must "
                + "fork a variant instead.", example = "1", requiredMode =
                Schema.RequiredMode.REQUIRED)
        @NotNull(message = "networkId is required")
        Long networkId,

        @Schema(description = "Disruption scenario to apply. Must belong to the network's project. "
                + "Omit — or send null — for the undisrupted baseline run of FR-17: N replications, "
                + "no paired set, and no disruption-relative metric rows. Replications and seed then "
                + "come from params, falling back to the engine defaults.",
                example = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long scenarioId,

        @Schema(description = "Optional per-run overrides; omit for the scenario's own settings.")
        @Valid
        SimulationParamsDto params) {
}
