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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * The product catalogue and the per-node parameters drawn from it (FR-01).
 *
 * <p><strong>A deliberate deviation.</strong> The obvious REST shape would list products
 * beside nodes and links under {@code /networks/{id}/...}, but the ER model scopes
 * {@code PRODUCT} to a {@code PROJECT}, and comparison relies on that: a configuration variant is a
 * different structure over the <em>same</em> catalogue, which is what makes two variants'
 * cost metrics comparable. Writes therefore live under {@code /projects/{id}/products}, following
 * the data model rather than that shape. {@code GET /networks/{id}/products} is kept as a
 * convenience for the editor, which holds a network id and would otherwise fetch the network only
 * to learn its project.
 *
 * <p>Per-node parameters are keyed by the natural {@code (node, product)} pair, so the URL names a
 * row completely and {@code PUT} is an upsert.
 */
@Tag(name = "Products",
        description = "The project's product catalogue, and per-node demand, inventory and holding "
                + "cost (FR-01).")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductController {

    private final ProductService products;
    private final CurrentUser currentUser;

    ProductController(ProductService products, CurrentUser currentUser) {
        this.products = products;
        this.currentUser = currentUser;
    }

    // ---------------------------------------------------------------- products

    @Operation(summary = "List a project's products", description = "Ordered by name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The project's products.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ProductDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/projects/{projectId}/products")
    public List<ProductDto> listByProject(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId) {
        return products.listByProject(projectId, currentUser.ownerId());
    }

    @Operation(summary = "List the products available to a network",
            description = "The catalogue of the network's project. Same rows as "
                    + "`GET /api/v1/projects/{projectId}/products`, reachable from a network id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The catalogue of the network's project.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ProductDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/products")
    public List<ProductDto> listByNetwork(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return products.listByNetwork(networkId, currentUser.ownerId());
    }

    @Operation(summary = "Add a product to a project",
            description = "Project-scoped, so every configuration variant of the project can use "
                    + "it and their metrics stay comparable. Not blocked by a frozen "
                    + "network — creating a product changes no network's structure.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the new product.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProductDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, or `unitValue` negative.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` — the project has a "
                    + "product with that name.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/projects/{projectId}/products",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductDto> create(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId,
            @Valid @RequestBody ProductRequest request) {
        ProductDto created = products.create(projectId, currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @Operation(summary = "Fetch one product")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The product.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProductDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such product for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/products/{productId}")
    public ProductDto get(
            @Parameter(description = "Product id.", example = "1") @PathVariable long productId) {
        return products.get(productId, currentUser.ownerId());
    }

    @Operation(summary = "Replace a product")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated product.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProductDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, or `unitValue` negative.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such product for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/products/{productId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProductDto replace(
            @Parameter(description = "Product id.", example = "1") @PathVariable long productId,
            @Valid @RequestBody ProductRequest request) {
        return products.replace(productId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Delete a product",
            description = "Its per-node rows cascade across every network of the project.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such product for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/products/{productId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Product id.", example = "1") @PathVariable long productId) {
        products.delete(productId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------- node products

    @Operation(summary = "List a node's per-product parameters",
            description = "Demand, inventory and holding cost for every product carried at this "
                    + "node (`NODE_PRODUCT`).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The node's per-product rows.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = NodeProductDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such node for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/nodes/{nodeId}/products")
    public List<NodeProductDto> listNodeProducts(
            @Parameter(description = "Node id.", example = "3") @PathVariable long nodeId) {
        return products.listByNode(nodeId, currentUser.ownerId());
    }

    @Operation(summary = "Fetch one node's parameters for one product")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The row.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NodeProductDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such node for this user, or the "
                    + "node has no parameters for that product.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/nodes/{nodeId}/products/{productId}")
    public NodeProductDto getNodeProduct(
            @Parameter(description = "Node id.", example = "3") @PathVariable long nodeId,
            @Parameter(description = "Product id.", example = "1") @PathVariable long productId) {
        return products.getNodeProduct(nodeId, productId, currentUser.ownerId());
    }

    @Operation(summary = "Set a node's parameters for a product",
            description = """
                    An upsert: `(node, product)` is the natural primary key, so the URL names the \
                    row completely and this creates it on first call and replaces it afterwards.

                    `demand` is what makes a CUSTOMER node a demand sink, and carries the unit it \
                    is measured over — 120 a week means the same thing whether the network steps \
                    in days or in weeks. The inventory fields apply to supply-side \
                    nodes, where stock acts as an additional supply source in the period after it \
                    is stocked, and are stock levels rather than flows, so no unit applies.

                    The product must belong to the same project as the node — otherwise \
                    `PRODUCT_OUT_OF_SCOPE` (422).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Created or replaced.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NodeProductDto.class))),
            @ApiResponse(responseCode = "400", description = "A value is negative.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such node or product for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "`PRODUCT_OUT_OF_SCOPE` — the product "
                    + "belongs to a different project from the node.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/nodes/{nodeId}/products/{productId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public NodeProductDto upsertNodeProduct(
            @Parameter(description = "Node id.", example = "3") @PathVariable long nodeId,
            @Parameter(description = "Product id.", example = "1") @PathVariable long productId,
            @Valid @RequestBody NodeProductRequest request) {
        return products.upsertNodeProduct(nodeId, productId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Remove a product from a node")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such node for this user, or the "
                    + "node has no parameters for that product.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/nodes/{nodeId}/products/{productId}")
    public ResponseEntity<Void> deleteNodeProduct(
            @Parameter(description = "Node id.", example = "3") @PathVariable long nodeId,
            @Parameter(description = "Product id.", example = "1") @PathVariable long productId) {
        products.deleteNodeProduct(nodeId, productId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }
}
