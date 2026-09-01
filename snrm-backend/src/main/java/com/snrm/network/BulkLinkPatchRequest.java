package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code PATCH /api/v1/networks/{networkId}/links} — a batch of link attribute edits.
 *
 * <p>The link counterpart of {@link BulkNodePatchRequest}, and the one the property panel uses when
 * a multi-select spans arcs — raising the lead time on every inbound arc of a distribution centre,
 * for instance. Applied whole or not at all.
 */
@Schema(name = "BulkLinkPatchRequest",
        description = "A batch of link attribute edits from the canvas editor.")
public record BulkLinkPatchRequest(

        @Schema(description = "The edited links. Every id must belong to the network in the path; "
                + "ids may not repeat within one batch.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "links must contain at least one entry")
        @Size(max = BulkNodePositionRequest.MAX_BATCH,
                message = "links must contain at most " + BulkNodePositionRequest.MAX_BATCH + " entries")
        @Valid
        List<LinkPatch> links) {
}
