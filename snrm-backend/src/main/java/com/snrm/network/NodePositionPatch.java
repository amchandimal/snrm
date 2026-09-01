package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * One node's new canvas coordinates in a batched move.
 *
 * <p>Both coordinates are required. A move is not a partial edit — the editor always knows where it
 * put the node, and a half-specified position would silently leave the node on one axis of its old
 * location.
 *
 * @param nodeId the node that moved
 * @param posX   new canvas x-coordinate
 * @param posY   new canvas y-coordinate
 */
@Schema(name = "NodePositionPatch", description = "New canvas coordinates for one node.")
public record NodePositionPatch(

        @Schema(description = "Id of the node that moved. Must belong to the network in the path.",
                example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "nodeId is required")
        Long nodeId,

        @Schema(description = "New canvas x-coordinate.", example = "240.0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "posX is required")
        Double posX,

        @Schema(description = "New canvas y-coordinate.", example = "160.0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "posY is required")
        Double posY) {
}
