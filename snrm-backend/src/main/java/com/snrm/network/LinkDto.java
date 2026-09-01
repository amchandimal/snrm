package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A link as the API returns it ({@code LINK}, DTO boundary).
 *
 * <p>{@link #leadTime} is a real duration in the unit it was entered in, not a period count:
 * the canvas labels an arc "6 h" because that is what the user said, and how many
 * simulation steps that becomes is a property of the network's clock rather than of the arc.
 * {@code GET /networks/{id}/time-validation} answers the second question.
 *
 * @param id           surrogate key
 * @param networkId    owning network; both endpoints belong to it
 * @param sourceNodeId upstream endpoint
 * @param targetNodeId downstream endpoint
 * @param leadTime     transit time in its own unit
 * @param capacity     throughput ceiling over its own unit; a null value means unconstrained
 * @param unitCost       cost per unit shipped
 * @param failureProb    independent per-period failure probability in [0,1]
 * @param caption        the annotation of FR-30, or null when there is none
 * @param captionVisible whether the canvas draws it; an empty caption draws nothing either way
 * @param createdAt      audit timestamp
 * @param updatedAt      audit timestamp
 */
@Schema(name = "Link", description = "A directed transport arc between two nodes of the same "
        + "network.")
public record LinkDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Owning network.", example = "1")
        Long networkId,

        @Schema(description = "Upstream endpoint.", example = "1")
        Long sourceNodeId,

        @Schema(description = "Downstream endpoint.", example = "2")
        Long targetNodeId,

        @Schema(description = "Transit time, in the unit it was entered in. Flow arrives that much "
                + "later, held as pipeline inventory in between. Zero means "
                + "same-period arrival.")
        DurationDto leadTime,

        @Schema(description = "Throughput ceiling, over the unit it was entered in. A null `value` "
                + "inside means unconstrained.")
        RateDto capacity,

        @Schema(description = "Cost per unit shipped along this arc.", example = "1.5")
        double unitCost,

        @Schema(description = "Independent per-period failure probability, in [0,1].",
                example = "0.01")
        double failureProb,

        @Schema(description = "Short annotation drawn beneath the arc's existing label — its "
                + "declared lead time (FR-30). Null when there is none.",
                example = "Ocean leg — single carrier", maxLength = 200, nullable = true)
        String caption,

        @Schema(description = "Whether the canvas draws the caption; an empty caption draws nothing "
                + "either way.", example = "true", defaultValue = "true")
        boolean captionVisible,

        @Schema(description = "When the link was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the link last changed (UTC).")
        Instant updatedAt) {
}
