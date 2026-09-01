package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of a batched node move or edit.
 *
 * <p>Returns the nodes as they now stand rather than an acknowledgement. The editor holds optimistic
 * local state and a client-side undo stack; getting the server's version back is what lets it
 * reconcile the two — and after a bulk edit it is also how the client learns the {@code updatedAt}
 * values it needs to detect a later conflict.
 *
 * @param updated how many nodes were written
 * @param nodes   those nodes, after the change
 */
@Schema(name = "BulkNodeResponse", description = "The nodes a batched edit wrote, as they now stand.")
public record BulkNodeResponse(

        @Schema(description = "Number of nodes written.", example = "3")
        int updated,

        @Schema(description = "The written nodes, in the order they were sent.")
        List<NodeDto> nodes) {

    static BulkNodeResponse of(List<NodeDto> nodes) {
        return new BulkNodeResponse(nodes.size(), nodes);
    }
}
