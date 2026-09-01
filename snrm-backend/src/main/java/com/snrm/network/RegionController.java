package com.snrm.network;

import com.snrm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The region tags of a network, and the node sets they resolve to.
 *
 * <p>These exist for the scenario builder. A {@code REGION}-scoped disruption event names a
 * {@code node.region} tag rather than an id, which is how a correlated geographic disruption is
 * expressed — one earthquake, every plant in the region — and the cost of that indirection is that
 * nothing on the event says what it actually hits. The timeline needs both halves: the catalogue to
 * offer as rows, and the resolution to preview beneath the bar before it is saved.
 *
 * <p><strong>The tag is a query parameter, not a path segment.</strong> A region is free text a
 * researcher typed or a spreadsheet column supplied — "EU-West", but equally "Asia /
 * Pacific" or "Zone 3 (coastal)". A path segment would put a slash-bearing tag through URL decoding
 * on every hop and make the difference between a tag and a route an escaping question; a query
 * parameter carries it verbatim.
 */
@Tag(name = "Regions",
        description = "The `node.region` tags in use in a network, and the nodes each one covers — "
                + "what a REGION-scoped disruption event resolves to.")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class RegionController {

    private final RegionService regions;
    private final CurrentUser currentUser;

    RegionController(RegionService regions, CurrentUser currentUser) {
        this.regions = regions;
        this.currentUser = currentUser;
    }

    @Operation(summary = "List the region tags in use in a network",
            description = "Alphabetical, each with the number of nodes carrying it — the row picker "
                    + "of the scenario builder's timeline. Derived from the nodes rather "
                    + "than stored, so retagging the last node of a region removes it from this "
                    + "list; there is no region entity to leave behind.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tags in use, alphabetically.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = RegionDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/regions")
    public List<RegionDto> catalogue(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return regions.catalogue(networkId, currentUser.ownerId());
    }

    @Operation(summary = "Resolve a region tag to its nodes",
            description = """
                    What a REGION-scoped disruption event on this tag would strike, in this \
                    network. The scenario builder shows it beneath the bar before the event is \
                    saved, so "every node in EU-West" is a list of names rather than a promise \


                    It runs the resolution itself — `region = ?` within one network — and not a \
                    description of it, so the preview cannot drift from what a simulation run will \
                    expand the event into.

                    A tag no node carries returns 200 with an empty `nodes` array and \
                    `nodeCount: 0`, not a 404: the tag is a value the caller supplied, not a \
                    resource, and that answer is exactly what the builder needs to warn about. \
                    Saving such an event is a different matter — the scenario endpoints refuse it \
                    with `EVENT_TARGET_INVALID`, because an event that strikes nothing produces a \
                    result that looks like resilience.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The nodes carrying the tag, possibly none.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RegionNodesDto.class))),
            @ApiResponse(responseCode = "400", description = "`region` not supplied.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/region-nodes")
    public RegionNodesDto resolve(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Parameter(description = "The `node.region` tag to resolve. A query parameter rather "
                    + "than a path segment because a tag is free text and may contain slashes, "
                    + "spaces or punctuation.", example = "EU-West", required = true)
            @RequestParam String region) {
        return regions.resolve(networkId, region, currentUser.ownerId());
    }
}
