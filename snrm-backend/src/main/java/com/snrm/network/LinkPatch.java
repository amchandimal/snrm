package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One link's changed attributes in a batched edit.
 *
 * <p>Same contract as {@link NodePatch}: omitted means unchanged, and a null cannot clear a
 * nullable field — {@code PUT /api/v1/links/{linkId}} does that. Endpoints are not patchable, for
 * the reason {@link LinkAttributesRequest} gives.
 *
 * <p>{@link #caption} carries the same single exception {@link NodePatch#caption()} documents: a
 * present-but-empty caption clears it (FR-30).
 */
@Schema(name = "LinkPatch",
        description = "Changed attributes of one link. Omitted fields are left unchanged.")
public record LinkPatch(

        @Schema(description = "Id of the link to edit. Must belong to the network in the path.",
                example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "linkId is required")
        Long linkId,

        @Schema(description = "New transit time and its unit, sent whole.",
                nullable = true)
        @Valid
        DurationDto leadTime,

        @Schema(description = "New throughput ceiling and the unit it is measured over, sent whole.",
                nullable = true)
        @Valid
        RateDto capacity,

        @Schema(description = "New cost per unit shipped.", example = "1.9", nullable = true)
        Double unitCost,

        @Schema(description = "New per-period failure probability.", example = "0.03",
                minimum = "0", maximum = "1", nullable = true)
        @DecimalMin(value = "0", message = "failureProb must be between 0 and 1")
        @DecimalMax(value = "1", message = "failureProb must be between 0 and 1")
        Double failureProb,

        @Schema(description = "New caption (FR-30). Omit to leave it alone; send an empty string to "
                + "CLEAR it.", example = "Ocean leg — single carrier", maxLength = 200,
                nullable = true)
        @Size(max = 200, message = "caption must be at most 200 characters")
        String caption,

        @Schema(description = "New visibility for the caption; omitted leaves it unchanged.",
                example = "false", nullable = true)
        Boolean captionVisible) {
}
