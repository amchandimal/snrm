package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RoundingPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A network as the API returns it ({@code NETWORK}, DTO boundary).
 *
 * <p>{@link #editable} is computed, not stored. The editor asks for it before the user's first
 * keystroke so it can offer the fork prompt instead of letting an edit fail; it is false
 * exactly when a simulation run holds the network frozen.
 *
 * <p>{@link #periodLength}, {@link #horizonPeriods} and {@link #roundingPolicy} are the network's
 * clock. They travel with every network response because almost nothing else in the API
 * can be read without them: a lead time of "36 HOUR" only becomes a number of simulation steps next
 * to the period it will be divided by, and the property panel's unit dropdowns default to the period
 * unit. They are set through {@code PUT /networks/{id}/time-base}, not here.
 *
 * @param id             surrogate key
 * @param projectId      owning project
 * @param name           unique with {@link #version} inside the project
 * @param version        variant generation within {@code (project, name)} — not an optimistic lock
 * @param baseline       whether the comparison view measures other variants against this one
 * @param editable       whether structural edits are currently allowed
 * @param periodLength   length of one simulation period
 * @param horizonPeriods how many periods a run covers
 * @param roundingPolicy how a duration that does not divide evenly into the period is rounded
 * @param createdAt      audit timestamp
 * @param updatedAt      audit timestamp
 */
@Schema(name = "Network",
        description = "One configuration of the supply network — the aggregate over its nodes and "
                + "links.")
public record NetworkDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Owning project.", example = "1")
        Long projectId,

        @Schema(description = "Network name; unique with `version` inside the project.",
                example = "Baseline")
        String name,

        @Schema(description = "Variant generation within (project, name). The baseline is 1 and "
                + "each clone takes the next number.", example = "1")
        int version,

        @Schema(description = "True for the network the comparison view measures the others "
                + "against. At most one per project.", example = "true")
        boolean baseline,

        @Schema(description = "False once a simulation run has frozen this network. "
                + "Every mutating call then fails with `NETWORK_IMMUTABLE`; clone it instead.",
                example = "true")
        boolean editable,

        @Schema(description = "Length of one simulation period — the grid every duration in this "
                + "network is discretised against.")
        DurationDto periodLength,

        @Schema(description = "How many periods a run over this network covers.",
                example = "52")
        int horizonPeriods,

        @Schema(description = "How a duration that does not divide evenly into the period is "
                + "rounded. A property of the network, not of each duration, so two variants "
                + "compared side by side discretise identically.",
                example = "NEAREST")
        RoundingPolicy roundingPolicy,

        @Schema(description = "When the network was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the network last changed (UTC).")
        Instant updatedAt) {
}
