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
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Links — the directed arcs of the network ({@code CRUD /networks/{id}/links}).
 *
 * <p>Creation is the strictest operation in the API. Three rules are enforced, each of which the
 * editor also blocks at the gesture level so a user never reaches them:
 *
 * <ul>
 *   <li>a link may not start and end at the same node — 422 {@code LINK_SELF_LOOP};</li>
 *   <li>both endpoints must belong to the network in the path — 422 {@code LINK_CROSS_NETWORK};</li>
 *   <li>an ordered pair may be connected only once — 409 {@code LINK_DUPLICATE}.</li>
 * </ul>
 *
 * <p>Endpoints cannot be changed after creation. Repointing an arc is not an edit of that arc; it
 * is a different topology, and any result computed over the old one would quietly stop matching.
 * Delete and redraw.
 */
@Tag(name = "Links",
        description = "Directed transport arcs between nodes of one network (FR-01, "
                + "FR-03).")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class LinkController {

    private final LinkService links;
    private final CurrentUser currentUser;

    LinkController(LinkService links, CurrentUser currentUser) {
        this.links = links;
        this.currentUser = currentUser;
    }

    @Operation(summary = "List a network's links")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The network's links.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = LinkDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/links")
    public List<LinkDto> listByNetwork(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return links.listByNetwork(networkId, currentUser.ownerId());
    }

    @Operation(summary = "Connect two nodes",
            description = """
                    The edge-handle gesture as a request. Rejects, in this order: \
                    self-loops (`LINK_SELF_LOOP`, 422), endpoints from another network \
                    (`LINK_CROSS_NETWORK`, 422) and a pair that is already connected \
                    (`LINK_DUPLICATE`, 409, carrying the existing link's id).

                    The pair is ordered, so `A → B` and `B → A` are different links and both may \
                    exist.

                    Echelon direction is *not* enforced: the editor blocks a link into a SUPPLIER \
                    or out of a CUSTOMER and only warns about lateral arcs such as DC → DC. \
                    A research tool that refused to model an unconventional topology would be \
                    refusing the experiment.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the new link.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LinkDto.class))),
            @ApiResponse(responseCode = "400", description = "An endpoint is missing, or a value is "
                    + "out of range.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user, or an "
                    + "endpoint matches no node at all.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`LINK_DUPLICATE` — the ordered pair is "
                    + "already connected; or `NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "`LINK_SELF_LOOP` — source and target "
                    + "are the same node; or `LINK_CROSS_NETWORK` — an endpoint belongs to a "
                    + "different network.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/networks/{networkId}/links",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkDto> create(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Valid @RequestBody LinkRequest request) {
        LinkDto created = links.create(networkId, currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/links/" + created.id())).body(created);
    }

    @Operation(summary = "Fetch one link")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The link.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LinkDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such link for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/links/{linkId}")
    public LinkDto get(
            @Parameter(description = "Link id.", example = "1") @PathVariable long linkId) {
        return links.get(linkId, currentUser.ownerId());
    }

    @Operation(summary = "Replace a link's attributes",
            description = "Full replacement: an omitted `capacity` is cleared back to "
                    + "unconstrained and an omitted `leadTime` to zero transit, both stated in the "
                    + "network's period unit. The endpoints are not part of the body and "
                    + "cannot be changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated link.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LinkDto.class))),
            @ApiResponse(responseCode = "400", description = "A value is out of range.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such link for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/links/{linkId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LinkDto replace(
            @Parameter(description = "Link id.", example = "1") @PathVariable long linkId,
            @Valid @RequestBody LinkAttributesRequest request) {
        return links.replace(linkId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Delete a link")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such link for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/links/{linkId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Link id.", example = "1") @PathVariable long linkId) {
        links.delete(linkId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bulk-patch link attributes",
            description = """
                    The link counterpart of the node bulk patch — what a multi-selection \
                    spanning arcs sends, such as raising the lead time on every inbound arc of a \
                    distribution centre.

                    Omitted fields are left unchanged; use `PUT /api/v1/links/{linkId}` to clear a \
                    capacity. Applied whole or not at all, and every id must belong to the network \
                    in the path.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All edits applied.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BulkLinkResponse.class))),
            @ApiResponse(responseCode = "400", description = "Empty batch, over the size limit, a "
                    + "value out of range, or the same link id twice.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user, or an "
                    + "id in the batch matches no link.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "`LINK_CROSS_NETWORK` — an id in the "
                    + "batch belongs to a different network.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping(path = "/api/v1/networks/{networkId}/links",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public BulkLinkResponse patchLinks(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Valid @RequestBody BulkLinkPatchRequest request) {
        return BulkLinkResponse.of(
                links.patchAll(networkId, currentUser.ownerId(), request.links()));
    }
}
