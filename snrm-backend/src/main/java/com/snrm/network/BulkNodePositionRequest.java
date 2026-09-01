package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code PATCH /api/v1/networks/{networkId}/nodes/positions} — a batch of canvas moves.
 *
 * <p>This is the endpoint the editor's debounce timer fires into. Dragging a box-selection of forty
 * nodes produces forty coordinate changes within one gesture; sending them as forty requests would
 * put forty transactions and forty immutability checks behind a single drag.
 *
 * <p>The batch is applied whole or not at all, so a rejected element never leaves the canvas and the
 * database disagreeing about where a node is. {@link #MAX_BATCH} bounds a single request; a client
 * with more to send splits it.
 */
@Schema(name = "BulkNodePositionRequest",
        description = "A batch of node moves from the canvas editor.")
public record BulkNodePositionRequest(

        @Schema(description = "The moved nodes. Every id must belong to the network in the path.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "positions must contain at least one entry")
        @Size(max = BulkNodePositionRequest.MAX_BATCH,
                message = "positions must contain at most " + BulkNodePositionRequest.MAX_BATCH + " entries")
        @Valid
        List<NodePositionPatch> positions) {

    /**
     * Largest batch accepted in one request. Sized against the 1,000-node networks of FR-04, so a
     * whole network can be laid out in one call — {@code cytoscape-dagre} auto-layout does exactly
     * that — while still bounding what one request can hold in memory.
     */
    public static final int MAX_BATCH = 1000;
}
