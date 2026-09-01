package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code PATCH /api/v1/networks/{networkId}/nodes} — a batch of attribute edits.
 *
 * <p>What the property side panel sends when it flushes: the editor debounces edits and PATCHes
 * every two seconds and on blur, so one request typically carries several nodes touched during that
 * window, and multi-select edits carry the whole selection.
 *
 * <p>Applied whole or not at all. A partially applied batch would leave the client's undo stack
 * describing a state the server never reached.
 */
@Schema(name = "BulkNodePatchRequest",
        description = "A batch of node attribute edits from the canvas editor.")
public record BulkNodePatchRequest(

        @Schema(description = "The edited nodes. Every id must belong to the network in the path; "
                + "ids may not repeat within one batch.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "nodes must contain at least one entry")
        @Size(max = BulkNodePositionRequest.MAX_BATCH,
                message = "nodes must contain at most " + BulkNodePositionRequest.MAX_BATCH + " entries")
        @Valid
        List<NodePatch> nodes) {
}
