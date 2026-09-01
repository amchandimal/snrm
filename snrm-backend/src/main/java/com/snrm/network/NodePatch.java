package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One node's changed attributes in a batched edit.
 *
 * <p><strong>Omitted means unchanged.</strong> Every field but {@link #nodeId} is nullable, and a
 * null is skipped rather than written. That is what makes this safe for the property panel's bulk
 * mode: selecting twelve nodes and setting a capacity should change their capacity and nothing
 * else, even though the twelve differ in every other attribute.
 *
 * <p>The corollary is that a PATCH cannot <em>clear</em> a nullable field — null already means
 * "leave alone". Use {@code PUT /api/v1/nodes/{nodeId}} to unset a capacity, a region or a
 * coordinate.
 *
 * <p><strong>{@link #caption} is the one exception, and it is deliberate (FR-30).</strong> A
 * <em>present but empty</em> caption clears it; null still means unchanged. A caption is
 * an ordinary edit written through this endpoint, and the editor has to be able to remove one — so
 * either this DTO says what an empty string means, or clearing a caption becomes the single caption
 * edit that goes through a different endpoint and replaces every other attribute of the node on the
 * way. The blank is a write here rather than the refusal {@link #name} gives it because a nameless
 * node is not a thing this API should produce and a captionless one is the normal case.
 * {@code Captions} states the rule for every path.
 *
 * <p>{@link #posX} / {@link #posY} may be sent here too, but the editor's drag handler should
 * prefer {@code PATCH /networks/{networkId}/nodes/positions}, which carries a tenth of the payload
 * per moved node.
 */
@Schema(name = "NodePatch",
        description = "Changed attributes of one node. Omitted fields are left unchanged; a PATCH "
                + "cannot clear a nullable field (use PUT for that).")
public record NodePatch(

        @Schema(description = "Id of the node to edit. Must belong to the network in the path.",
                example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "nodeId is required")
        Long nodeId,

        // @Pattern rather than @NotBlank: a null here means "leave the name alone", which is the
        // whole point of a patch, and @NotBlank rejects null. The expression demands one
        // non-whitespace character from a name that IS present.
        @Schema(description = "New name; must stay unique within the network.", example = "SUP-1a",
                maxLength = 120, nullable = true)
        @Pattern(regexp = ".*\\S.*", message = "name must not be blank when present")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Schema(description = "New echelon.", example = "PLANT", nullable = true)
        NodeType type,

        @Schema(description = "New throughput ceiling and the unit it is measured over. Sent whole "
                + "— there is no way to change a rate's unit without restating its value, and "
                + "nothing sensible to do with half of the pair.", nullable = true)
        @Valid
        RateDto capacity,

        @Schema(description = "New dwell before material can leave, sent whole.",
                nullable = true)
        @Valid
        DurationDto processingTime,

        @Schema(description = "New per-period fixed cost.", example = "1200.0", nullable = true)
        Double fixedCost,

        @Schema(description = "New per-unit variable cost.", example = "2.75", nullable = true)
        Double varCost,

        @Schema(description = "New per-period failure probability.", example = "0.05",
                minimum = "0", maximum = "1", nullable = true)
        @DecimalMin(value = "0", message = "failureProb must be between 0 and 1")
        @DecimalMax(value = "1", message = "failureProb must be between 0 and 1")
        Double failureProb,

        @Schema(description = "New geographic tag.", example = "EU-North", maxLength = 60,
                nullable = true)
        @Size(max = 60, message = "region must be at most 60 characters")
        String region,

        @Schema(description = "New latitude.", example = "59.3293", minimum = "-90", maximum = "90",
                nullable = true)
        @DecimalMin(value = "-90", message = "lat must be between -90 and 90")
        @DecimalMax(value = "90", message = "lat must be between -90 and 90")
        Double lat,

        @Schema(description = "New longitude.", example = "18.0686", minimum = "-180",
                maximum = "180", nullable = true)
        @DecimalMin(value = "-180", message = "lng must be between -180 and 180")
        @DecimalMax(value = "180", message = "lng must be between -180 and 180")
        Double lng,

        @Schema(description = "New canvas x-coordinate. Prefer the positions endpoint for moves.",
                example = "240.0", nullable = true)
        Double posX,

        @Schema(description = "New canvas y-coordinate. Prefer the positions endpoint for moves.",
                example = "160.0", nullable = true)
        Double posY,

        @Schema(description = "New caption (FR-30). Omit to leave it alone; send an empty string to "
                + "CLEAR it — the one field on this DTO where a present-but-empty value is a write "
                + "rather than a refusal, because the editor writes caption edits through this "
                + "endpoint and has to be able to remove one.",
                example = "Nordic hub — 3PL operated", maxLength = 200, nullable = true)
        @Size(max = 200, message = "caption must be at most 200 characters")
        String caption,

        @Schema(description = "New visibility for the caption. Omitted leaves it unchanged. Bulk-"
                + "applies across a multi-selection, unlike the caption text, because hiding every "
                + "caption at once is exactly what preparing a figure wants.",
                example = "false", nullable = true)
        Boolean captionVisible) {
}
