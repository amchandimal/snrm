package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A node as the API returns it ({@code NODE}, DTO boundary).
 *
 * <p>{@link #capacity} and {@link #processingTime} come back in the unit they were entered in, never
 * restated into periods: the editor's property panel shows the number the user typed
 * next to a unit dropdown, and a node whose capacity was set as 500 per hour must not read back as
 * 12,000 per day. What the engine will do with them is a different question, answered by
 * {@code GET /networks/{id}/time-validation}.
 *
 * @param id             surrogate key
 * @param networkId      owning network
 * @param name           unique within the network ({@code uq_node})
 * @param type           echelon: SUPPLIER, PLANT, DC or CUSTOMER
 * @param capacity       throughput ceiling over its own unit; a null value means unconstrained
 * @param processingTime dwell before material can leave, in its own unit
 * @param fixedCost      per-period fixed cost of operating the node
 * @param varCost        per-unit variable cost
 * @param failureProb    independent per-period failure probability in [0,1]
 * @param region         geographic tag REGION-scoped disruptions resolve through
 * @param lat            latitude
 * @param lng            longitude
 * @param posX           editor canvas x-coordinate
 * @param posY           editor canvas y-coordinate
 * @param caption        the annotation of FR-30, or null when there is none
 * @param captionVisible whether the canvas draws it; an empty caption draws nothing either way
 * @param createdAt      audit timestamp
 * @param updatedAt      audit timestamp
 */
@Schema(name = "Node",
        description = "A facility in the network — supplier, plant, DC or customer.")
public record NodeDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Owning network.", example = "1")
        Long networkId,

        @Schema(description = "Node name; unique within the network. Import resolves link endpoints "
                + "by this name.", example = "SUP-1")
        String name,

        @Schema(description = "Echelon the node occupies.", example = "SUPPLIER")
        NodeType type,

        @Schema(description = "Throughput ceiling, over the unit it was entered in. A null `value` "
                + "inside means unconstrained.")
        RateDto capacity,

        @Schema(description = "Time material spends being handled here before it can leave — dwell "
                + "at a DC, cycle time at a plant. Zero means no dwell.")
        DurationDto processingTime,

        @Schema(description = "Per-period fixed cost of operating the node.", example = "1000.0")
        double fixedCost,

        @Schema(description = "Per-unit variable cost.", example = "2.5")
        double varCost,

        @Schema(description = "Independent per-period failure probability, in [0,1].",
                example = "0.02")
        double failureProb,

        @Schema(description = "Geographic tag. REGION-scoped disruption events resolve to node sets "
                + "through it, which is how correlated geographic disruptions are expressed.",
                example = "EU-West", nullable = true)
        String region,

        @Schema(description = "Latitude, if the node is geo-located.", example = "48.8566",
                nullable = true)
        Double lat,

        @Schema(description = "Longitude, if the node is geo-located.", example = "2.3522",
                nullable = true)
        Double lng,

        @Schema(description = "Editor canvas x-coordinate — layout, not geography. Persisted so a "
                + "manual layout survives a reload.", example = "120.0", nullable = true)
        Double posX,

        @Schema(description = "Editor canvas y-coordinate.", example = "80.0",
                nullable = true)
        Double posY,

        @Schema(description = "Short annotation drawn beneath the node's name in smaller, quieter "
                + "type (FR-30). Null when there is none — a blank caption is normalised "
                + "to null on every write path, so this is never an empty string.",
                example = "Nordic hub — 3PL operated", maxLength = 200, nullable = true)
        String caption,

        @Schema(description = "Whether the canvas draws the caption. An empty caption draws nothing "
                + "whatever this says, so the flag governs annotation that exists rather than "
                + "reserving space.", example = "true", defaultValue = "true")
        boolean captionVisible,

        @Schema(description = "When the node was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the node last changed (UTC).")
        Instant updatedAt) {
}
