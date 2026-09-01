package com.snrm.scenario;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A disruption scenario as the API returns it ({@code DISRUPTION_SCENARIO}, FR-05).
 *
 * <p>{@link #events()} is present on the single-scenario reads and absent from the list, where
 * {@link #eventCount()} stands in for it. The list is the picker in the scenario builder's sidebar
 * and wants a row per scenario, not every bar of every timeline; the count is what the row
 * shows, and it costs one aggregate rather than a collection fetch per scenario.
 *
 * @param id              surrogate key
 * @param projectId       owning project — scenarios are project-scoped so one can be replayed
 *                        against every configuration variant
 * @param name            unique within the project ({@code uq_scenario})
 * @param numReplications Monte Carlo replications per run
 * @param seed            base RNG seed, or null to draw one per run
 * @param eventCount      how many events the scenario holds
 * @param events          the events in timeline order, or null on a list response
 * @param createdAt       audit timestamp
 * @param updatedAt       audit timestamp
 */
@Schema(name = "DisruptionScenario",
        description = "A named set of disruption events plus its Monte Carlo settings.")
public record DisruptionScenarioDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Owning project. A scenario is project-scoped, not network-scoped, so "
                + "the same disruptions can be replayed against every configuration variant.",
                example = "1")
        Long projectId,

        @Schema(description = "Scenario name; unique within the project.",
                example = "Tier-1 plant fire")
        String name,

        @Schema(description = "Monte Carlo replications per simulation run.",
                example = "100")
        int numReplications,

        @Schema(description = "Base RNG seed. Null means a fresh seed is drawn per run; either way "
                + "the seed actually used is recorded on the run, which is what reproducibility "
                + "rests on.", example = "424242", nullable = true)
        Long seed,

        @Schema(description = "How many events the scenario holds.", example = "3")
        int eventCount,

        @Schema(description = "The events, earliest start offset first. Present on single-scenario "
                + "reads; omitted from the list response, where `eventCount` stands in for it.",
                nullable = true)
        List<DisruptionEventDto> events,

        @Schema(description = "When the scenario was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the scenario last changed (UTC).")
        Instant updatedAt) {
}
