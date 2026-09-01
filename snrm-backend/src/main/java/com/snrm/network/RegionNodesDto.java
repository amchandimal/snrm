package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What a {@code REGION}-scoped disruption event would actually strike: the nodes of one network
 * carrying one region tag.
 *
 * <p>This is the resolution itself, not a description of it — the endpoint runs the same query the
 * simulation engine will run when it expands a REGION event into node-level capacity reductions, so
 * the preview the scenario builder shows before saving cannot disagree with what the run does.
 *
 * <p>An empty {@link #nodes()} is a meaningful answer and not an error: it is what the builder shows
 * when the user picks a tag no node in <em>this</em> network carries, which is a real possibility
 * because a scenario is project-scoped and its regions were chosen against some other variant.
 * Saving such an event is refused ({@code EVENT_TARGET_INVALID}); looking at it is not.
 *
 * @param networkId the network the tag was resolved in
 * @param region    the tag that was asked for, echoed back
 * @param nodeCount how many nodes carry it — {@code nodes.size()}, stated so a client can show the
 *                  number without counting an array it may have collapsed
 * @param nodes     those nodes, in id order
 */
@Schema(name = "RegionNodes",
        description = "The nodes of a network carrying a region tag — what a REGION-scoped "
                + "disruption event resolves to.")
public record RegionNodesDto(

        @Schema(description = "The network the tag was resolved in.", example = "1")
        Long networkId,

        @Schema(description = "The tag that was asked for.", example = "EU-West")
        String region,

        @Schema(description = "How many nodes carry it. Zero is a valid answer — the tag is simply "
                + "not in use in this network.", example = "4")
        int nodeCount,

        @Schema(description = "The nodes themselves, in id order, so the builder can name them and "
                + "the canvas can highlight them.")
        List<NodeDto> nodes) {
}
