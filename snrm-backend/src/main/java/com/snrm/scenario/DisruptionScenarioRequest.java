package com.snrm.scenario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/projects/{projectId}/scenarios} and
 * {@code PUT /api/v1/scenarios/{scenarioId}}.
 *
 * <p>The scenario's own settings only. Events are addressed separately, under
 * {@code /scenarios/{id}/events}, because the timeline edits one bar at a time and
 * because an event has to be validated against a <em>network</em> — its target is a node or link id
 * and its window is measured against a horizon — which a scenario, being project-scoped, does not
 * name. Folding events into this body would mean either dropping those checks or requiring a network
 * to rename a scenario.
 */
@Schema(name = "DisruptionScenarioRequest",
        description = "A named set of disruption events plus its Monte Carlo settings.")
public record DisruptionScenarioRequest(

        @Schema(description = "Scenario name; unique within the project.",
                example = "Tier-1 plant fire", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @Schema(description = "Monte Carlo replications per simulation run. Omit for the "
                + "default of 100.", example = "100", defaultValue = "100", minimum = "1",
                nullable = true)
        @Min(value = 1, message = "numReplications must be at least 1")
        Integer numReplications,

        @Schema(description = "Base RNG seed. Omit to draw a fresh seed per run — the seed actually "
                + "used is recorded on the run either way, so reproducibility does not depend on "
                + "setting this.", example = "424242", nullable = true)
        Long seed) {
}
