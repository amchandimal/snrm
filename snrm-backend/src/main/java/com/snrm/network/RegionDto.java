package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One region tag in use in a network, and how many of its nodes carry it.
 *
 * <p>A region is a tag, not an entity: it has no id, no attributes and no row of its own — it exists
 * exactly as long as some node is tagged with it. So this record is the whole of what there is to
 * say about one, and the count is what makes the timeline's row picker useful ("EU-West · 4 nodes")
 * rather than a list of bare strings.
 *
 * @param region    the {@code node.region} value
 * @param nodeCount how many nodes of this network carry it — never zero, since the catalogue is
 *                  derived from the nodes themselves
 */
@Schema(name = "Region",
        description = "A region tag in use in a network, with the number of nodes carrying it.")
public record RegionDto(

        @Schema(description = "The `node.region` tag.", example = "EU-West")
        String region,

        @Schema(description = "How many nodes of this network carry the tag.", example = "4")
        long nodeCount) {
}
