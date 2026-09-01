package com.snrm.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.snrm.common.DurationDto;
import com.snrm.network.VariantOrigin;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One column of the comparison matrix: the configuration being compared, and what makes it that
 * configuration.
 *
 * <h2>The lever diff is the point of the column header</h2>
 *
 * <p>{@link #leverChanges} is {@code configuration_variant.lever_changes_json} — the structured diff
 * from the network this one was forked from. It is stored for one reason:
 *
 * <blockquote>a structured diff from the base network (e.g. added backup supplier, +20%
 * capacity at node 12) enables lever-level attribution of metric improvements.</blockquote>
 *
 * <p>Which is what turns the matrix from a scoreboard into a finding. A column that says only
 * "Baseline v3" reports that something improved; a column that says "+20% capacity at PLANT-1"
 * reports what improved it. A baseline network has no variant row and therefore no diff, and comes
 * back with {@code leverChanges: null} and {@code baseNetworkId: null}.
 *
 * <h2>The run is named, and may be absent</h2>
 *
 * <p>{@link #runId} is the completed run the simulated half of the column was read from — the most
 * recent {@code DONE} run of this network, restricted to one scenario when the request named one.
 * It is null for a network that has never been simulated, whose column then carries its topological
 * metrics and nothing else. That is a legitimate state to compare in: the structural half of the
 * suite is computed on save, so a freshly forked variant can be judged structurally before
 * anyone spends an hour of Monte Carlo on it.
 *
 * <p>{@link #periodLength} travels with the column because a time-valued metric cannot be read
 * without it — and because two columns whose period lengths differ are the condition
 * {@link ComparisonMatrixDto#mixedTimeBases()} warns about.
 *
 * @param networkId     the configuration
 * @param name          its name; variants of one network share it and differ in {@link #version}
 * @param version       variant generation within {@code (project, name)}
 * @param baseline      whether this is the network the others are measured against
 * @param baseNetworkId the network this was forked from; null for a baseline or an imported network
 * @param generatedBy   {@code MANUAL} for an editor fork, {@code SEARCH} for a Phase 2 candidate
 * @param leverChanges  the structured diff from {@link #baseNetworkId}; null where there is none
 * @param runId         the completed run the simulated metrics came from, or null
 * @param scenarioId    the scenario that run applied, or null
 * @param scenarioName  that scenario's name, so the column can be labelled without a second fetch
 * @param runFinishedAt when that run completed, or null
 * @param periodLength  this configuration's clock
 * @param horizonPeriods how many periods its runs cover
 */
@Schema(name = "ComparisonVariant",
        description = "One configuration in the comparison, with its lever diff from the network "
                + "it was forked from and the run its simulated metrics were read from.")
public record ComparisonVariantDto(

        @Schema(description = "The network being compared.", example = "4")
        Long networkId,

        @Schema(description = "Network name. Variants of one network share it.", example = "Baseline")
        String name,

        @Schema(description = "Variant generation within (project, name).", example = "2")
        int version,

        @Schema(description = "True for the project's baseline configuration.",
                example = "false")
        boolean baseline,

        @Schema(description = "The network this one was forked from; null for a baseline or an "
                + "imported network.", example = "1", nullable = true)
        Long baseNetworkId,

        @Schema(description = "How the variant came to exist: MANUAL for an editor fork, SEARCH "
                + "for a Phase 2 candidate. Null for a network that is not a variant.",
                example = "MANUAL", nullable = true)
        VariantOrigin generatedBy,

        @Schema(description = "Structured diff from the base network — the lever-change annotation "
                + "shown in the column header. Null for a baseline.", nullable = true)
        JsonNode leverChanges,

        @Schema(description = "The completed run the simulated metrics were read from. Null for a "
                + "network that has never been simulated; its column then carries only the "
                + "topological half of the suite.", example = "12", nullable = true)
        Long runId,

        @Schema(description = "The scenario that run applied.", example = "1", nullable = true)
        Long scenarioId,

        @Schema(description = "That scenario's name.", example = "Plant outage", nullable = true)
        String scenarioName,

        @Schema(description = "When that run completed (UTC).", nullable = true)
        Instant runFinishedAt,

        @Schema(description = "This configuration's period length. Two columns whose "
                + "period lengths differ are what `mixedTimeBases` warns about.")
        DurationDto periodLength,

        @Schema(description = "How many periods a run over this configuration covers.",
                example = "52")
        int horizonPeriods) {
}
