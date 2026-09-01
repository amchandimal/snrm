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
 * Nodes, including the batched canvas writes the editor depends on
 * ({@code CRUD /networks/{id}/nodes} with "bulk PUT/PATCH for canvas edits").
 *
 * <p><strong>Why two bulk endpoints rather than one.</strong> A drag and an attribute edit are
 * different in shape and in volume. Dragging a forty-node selection produces forty pairs of
 * numbers and nothing else, several times a second; editing the property panel produces a handful
 * of rows with arbitrary fields. Splitting them keeps the move payload small enough to send on a
 * two-second debounce, and keeps the attribute endpoint from having to guess whether an absent
 * coordinate means "did not move" or "move to null".
 *
 * <p><strong>PUT versus PATCH.</strong> PUT replaces a node whole — an omitted nullable field is
 * cleared, which is how a capacity or a region is unset. PATCH leaves omitted fields alone, which
 * is what a multi-selection edit needs. Neither verb can be used for the other's job.
 */
@Tag(name = "Nodes",
        description = "Facilities in a network, and the batched writes of the canvas editor "
                + "(FR-01, FR-03).")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class NodeController {

    private final NodeService nodes;
    private final CurrentUser currentUser;

    NodeController(NodeService nodes, CurrentUser currentUser) {
        this.nodes = nodes;
        this.currentUser = currentUser;
    }

    @Operation(summary = "List a network's nodes")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The network's nodes.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = NodeDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/nodes")
    public List<NodeDto> listByNetwork(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return nodes.listByNetwork(networkId, currentUser.ownerId());
    }

    @Operation(summary = "Add a node to a network",
            description = "The palette drag-and-drop: `posX`/`posY` are the drop "
                    + "coordinates, persisted so a manual layout survives a reload. Names are "
                    + "unique within the network — import resolves link endpoints by name.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the new node.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NodeDto.class))),
            @ApiResponse(responseCode = "400", description = "A field is missing or out of range.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` — the network has a "
                    + "node with that name; or `NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/networks/{networkId}/nodes",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NodeDto> create(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Valid @RequestBody NodeRequest request) {
        NodeDto created = nodes.create(networkId, currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/nodes/" + created.id())).body(created);
    }

    @Operation(summary = "Fetch one node")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The node.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NodeDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such node for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/nodes/{nodeId}")
    public NodeDto get(
            @Parameter(description = "Node id.", example = "1") @PathVariable long nodeId) {
        return nodes.get(nodeId, currentUser.ownerId());
    }

    @Operation(summary = "Replace a node",
            description = "Full replacement: an omitted nullable field — capacity, region, "
                    + "latitude, longitude, canvas coordinates — is **cleared**. This is the only "
                    + "way to unset one; PATCH cannot, since a null there means 'leave alone'.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated node.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NodeDto.class))),
            @ApiResponse(responseCode = "400", description = "A field is missing or out of range.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such node for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` or `NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/nodes/{nodeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NodeDto replace(
            @Parameter(description = "Node id.", example = "1") @PathVariable long nodeId,
            @Valid @RequestBody NodeRequest request) {
        return nodes.replace(nodeId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Delete a node",
            description = "Its links and per-product rows cascade with it. Scenario events "
                    + "targeting it are a soft reference and are listed to the user before the "
                    + "delete rather than removed.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such node for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/nodes/{nodeId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Node id.", example = "1") @PathVariable long nodeId) {
        nodes.delete(nodeId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------ batched canvas writes

    @Operation(summary = "Bulk-patch node positions (canvas moves)",
            description = """
                    The endpoint the editor's drag handler flushes into on a two-second debounce and \
                    on blur. One request carries every node moved in that window, which \
                    for a box-selection drag is the whole selection.

                    Applied in one transaction, whole or not at all: a half-applied batch would \
                    leave the client's undo stack describing a state the server never reached. \
                    Every id must belong to the network in the path and may appear only once.

                    Returns the moved nodes as they now stand, so the client can reconcile its \
                    optimistic local state.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All moves applied.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BulkNodeResponse.class))),
            @ApiResponse(responseCode = "400", description = "Empty batch, over the size limit, a "
                    + "missing coordinate, or the same node id twice.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user, or an "
                    + "id in the batch matches no node.",
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
    @PatchMapping(path = "/api/v1/networks/{networkId}/nodes/positions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public BulkNodeResponse patchPositions(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Valid @RequestBody BulkNodePositionRequest request) {
        return BulkNodeResponse.of(
                nodes.moveAll(networkId, currentUser.ownerId(), request.positions()));
    }

    @Operation(summary = "Bulk-patch node attributes",
            description = """
                    What the property side panel sends when it flushes. Omitted fields are \
                    left unchanged, which is what makes a multi-selection edit safe: setting one \
                    capacity across twelve nodes must not overwrite the eleven other attributes on \
                    which those nodes differ.

                    A PATCH therefore cannot **clear** a nullable field — use \
                    `PUT /api/v1/nodes/{nodeId}` for that.

                    Applied whole or not at all. Renames are checked against the rest of the network \
                    and against the other names in the same batch, so swapping two names in one \
                    request is rejected rather than half-applied.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All edits applied.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BulkNodeResponse.class))),
            @ApiResponse(responseCode = "400", description = "Empty batch, over the size limit, a "
                    + "value out of range, or the same node id twice.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user, or an "
                    + "id in the batch matches no node.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` or `NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "`LINK_CROSS_NETWORK` — an id in the "
                    + "batch belongs to a different network.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping(path = "/api/v1/networks/{networkId}/nodes",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public BulkNodeResponse patchNodes(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Valid @RequestBody BulkNodePatchRequest request) {
        return BulkNodeResponse.of(
                nodes.patchAll(networkId, currentUser.ownerId(), request.nodes()));
    }
}
