package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/networks/{networkId}/nodes} and {@code PUT /api/v1/nodes/{nodeId}}
 * — the complete client-supplied state of a node.
 *
 * <p><strong>PUT replaces.</strong> An omitted nullable field ({@link #capacity},
 * {@link #processingTime}, {@link #region}, {@link #lat}, {@link #lng}, {@link #posX},
 * {@link #posY}, {@link #caption}) is cleared, which is how a value is unset — the bulk PATCH
 * cannot express that for most fields, by design. The three cost and probability fields are
 * primitives and default to 0 when omitted, matching the column defaults in {@code V2__domain.sql},
 * so a node dropped on the canvas needs only a name and a type.
 *
 * <p><strong>The caption (FR-30).</strong> An omitted <em>or blank</em> {@link #caption} clears it;
 * a caption is trimmed and a blank one is stored as null, so there is one form of "no caption".
 * {@link #captionVisible} is the exception to "omitted is cleared": omitting it means
 * <em>visible</em>, matching the {@code NOT NULL DEFAULT TRUE} column of V10 and the omission rules.
 * The rule for every path is stated once in {@code Captions} — including that
 * the bulk PATCH <em>can</em> clear a caption, by sending an empty string, which is the
 * one field where it can clear anything.
 *
 * <p><strong>Units (FR-13).</strong> {@link #capacity} and {@link #processingTime} are
 * {@code {value, unit}} objects, and a unit is required whenever the object is present — a rate
 * without one is the ambiguity FR-13 exists to remove. Omitting the whole object is still allowed
 * and means the field's empty form (unconstrained, zero) stated in the network's own period unit,
 * which is also the unit the property panel's dropdown defaults to.
 *
 * <p>Ranges mirror the {@code CHECK} constraints of the schema, so a bad value comes back as a 400
 * naming the field rather than as a driver error — {@code capacity.value} for a nested one.
 * Latitude and longitude have no constraint in the schema but are range-checked here, per the
 * row-level validation the importer performs.
 */
@Schema(name = "NodeRequest",
        description = "Complete client-supplied state of a node. Used for create and for full "
                + "replacement; omitted nullable fields are cleared on PUT.")
public record NodeRequest(

        @Schema(description = "Node name; unique within the network.", example = "SUP-1",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 120)
        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Schema(description = "Echelon the node occupies.", example = "SUPPLIER",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "type is required")
        NodeType type,

        @Schema(description = "Throughput ceiling and the unit it is measured over, e.g. "
                + "`{\"value\": 500, \"timeUnit\": \"DAY\"}`. Omit for unconstrained.",
                nullable = true)
        @Valid
        RateDto capacity,

        @Schema(description = "Dwell before material can leave, e.g. "
                + "`{\"value\": 12, \"unit\": \"HOUR\"}`. Omit for none.",
                nullable = true)
        @Valid
        DurationDto processingTime,

        @Schema(description = "Per-period fixed cost of operating the node. Defaults to 0.",
                example = "1000.0", defaultValue = "0")
        double fixedCost,

        @Schema(description = "Per-unit variable cost. Defaults to 0.", example = "2.5",
                defaultValue = "0")
        double varCost,

        @Schema(description = "Independent per-period failure probability. Defaults to 0.",
                example = "0.02", defaultValue = "0", minimum = "0", maximum = "1")
        @DecimalMin(value = "0", message = "failureProb must be between 0 and 1")
        @DecimalMax(value = "1", message = "failureProb must be between 0 and 1")
        double failureProb,

        @Schema(description = "Geographic tag REGION-scoped disruptions resolve through.",
                example = "EU-West", maxLength = 60, nullable = true)
        @Size(max = 60, message = "region must be at most 60 characters")
        String region,

        @Schema(description = "Latitude.", example = "48.8566", minimum = "-90", maximum = "90",
                nullable = true)
        @DecimalMin(value = "-90", message = "lat must be between -90 and 90")
        @DecimalMax(value = "90", message = "lat must be between -90 and 90")
        Double lat,

        @Schema(description = "Longitude.", example = "2.3522", minimum = "-180", maximum = "180",
                nullable = true)
        @DecimalMin(value = "-180", message = "lng must be between -180 and 180")
        @DecimalMax(value = "180", message = "lng must be between -180 and 180")
        Double lng,

        @Schema(description = "Editor canvas x-coordinate; the drop point.",
                example = "120.0", nullable = true)
        Double posX,

        @Schema(description = "Editor canvas y-coordinate; the drop point.",
                example = "80.0", nullable = true)
        Double posY,

        @Schema(description = "Short annotation drawn beneath the node's name (FR-30). Omit — or "
                + "send an empty string — to clear it.",
                example = "Nordic hub — 3PL operated", maxLength = 200, nullable = true)
        @Size(max = 200, message = "caption must be at most 200 characters")
        String caption,

        @Schema(description = "Whether the canvas draws the caption. Omitting it means VISIBLE, not "
                + "hidden — an empty caption draws nothing either way, so the default that makes a "
                + "caption appear as it is typed is the useful one.",
                example = "true", defaultValue = "true", nullable = true)
        Boolean captionVisible) {
}
