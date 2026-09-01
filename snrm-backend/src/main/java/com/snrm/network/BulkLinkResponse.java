package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of a batched link edit — the link counterpart of {@link BulkNodeResponse}.
 *
 * @param updated how many links were written
 * @param links   those links, after the change
 */
@Schema(name = "BulkLinkResponse", description = "The links a batched edit wrote, as they now stand.")
public record BulkLinkResponse(

        @Schema(description = "Number of links written.", example = "2")
        int updated,

        @Schema(description = "The written links, in the order they were sent.")
        List<LinkDto> links) {

    static BulkLinkResponse of(List<LinkDto> links) {
        return new BulkLinkResponse(links.size(), links);
    }
}
