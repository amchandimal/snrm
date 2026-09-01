package com.snrm.network;

import com.snrm.common.DuplicateNameException;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The product catalogue and the per-node parameters drawn from it ({@code PRODUCT} and
 * {@code NODE_PRODUCT}, FR-01).
 *
 * <p>Products are <em>project</em>-scoped, not network-scoped, so that every configuration variant
 * of a project is a different structure over one catalogue and their metrics stay comparable.
 * Creating a product therefore does not touch a network and is not blocked by the
 * immutability rule; writing a {@code node_product} row does touch one, and is.
 *
 * <p>{@code node_product} has foreign keys to a node and to a product but nothing tying the two to
 * the same project. {@link #upsertNodeProduct} closes that gap — see {@link ProductScopeException}.
 */
@Service
@Transactional
public class ProductService {

    private final ProductRepository products;
    private final NodeProductRepository nodeProducts;
    private final ProjectRepository projects;
    private final NetworkMutationGuard guard;
    private final NetworkLookup lookup;
    private final ProductMapper mapper;

    ProductService(ProductRepository products, NodeProductRepository nodeProducts,
            ProjectRepository projects, NetworkMutationGuard guard, NetworkLookup lookup,
            ProductMapper mapper) {
        this.products = products;
        this.nodeProducts = nodeProducts;
        this.projects = projects;
        this.guard = guard;
        this.lookup = lookup;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- products

    @Transactional(readOnly = true)
    public List<ProductDto> listByProject(long projectId, long ownerId) {
        requireProject(projectId, ownerId);
        return mapper.toDtoList(products.findByProjectIdOrderByNameAsc(projectId));
    }

    /**
     * The catalogue available to a network — that is, its project's products.
     *
     * <p>The API surfaces products under {@code /networks/{id}/...} while the data model scopes
     * them to a project. Writes follow the data model and live under {@code /projects/{id}/products};
     * this read exists because the editor holds a network id and would otherwise have to fetch the
     * network only to learn its project.
     */
    @Transactional(readOnly = true)
    public List<ProductDto> listByNetwork(long networkId, long ownerId) {
        Network network = lookup.requireNetwork(networkId, ownerId);
        return mapper.toDtoList(
                products.findByProjectIdOrderByNameAsc(network.getProject().getId()));
    }

    @Transactional(readOnly = true)
    public ProductDto get(long productId, long ownerId) {
        return mapper.toDto(lookup.requireProduct(productId, ownerId));
    }

    /** @throws DuplicateNameException if the project already has a product with that name */
    public ProductDto create(long projectId, long ownerId, ProductRequest request) {
        Project project = requireProject(projectId, ownerId);
        String name = request.name().trim();
        if (products.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateNameException("product", "Project " + projectId, name);
        }
        return mapper.toDto(products.save(new Product(project, name, request.unitValue())));
    }

    public ProductDto replace(long productId, long ownerId, ProductRequest request) {
        Product product = lookup.requireProduct(productId, ownerId);
        long projectId = product.getProject().getId();
        String name = request.name().trim();
        if (!product.getName().equals(name) && products.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateNameException("product", "Project " + projectId, name);
        }
        mapper.replace(request, product);
        product.setName(name);
        return mapper.toDto(product);
    }

    /**
     * Deletes a product.
     *
     * <p>Its {@code node_product} rows go with it across every network of the project, cascaded by
     * {@code fk_node_product_product} — including rows on networks a simulation has frozen. That is
     * the one place the immutability rule can be reached around, and it is inherent to
     * the catalogue being project-scoped: the alternative is refusing to delete a product because
     * some unrelated variant was once simulated. Worth revisiting when the metric engine lands.
     */
    public void delete(long productId, long ownerId) {
        products.delete(lookup.requireProduct(productId, ownerId));
    }

    // ----------------------------------------------------------- node products

    @Transactional(readOnly = true)
    public List<NodeProductDto> listByNode(long nodeId, long ownerId) {
        lookup.requireNode(nodeId, ownerId);
        return mapper.toNodeProductDtoList(nodeProducts.findByNodeId(nodeId));
    }

    @Transactional(readOnly = true)
    public NodeProductDto getNodeProduct(long nodeId, long productId, long ownerId) {
        lookup.requireNode(nodeId, ownerId);
        return mapper.toDto(requireNodeProduct(nodeId, productId));
    }

    /**
     * Creates or replaces the parameters of one product at one node.
     *
     * <p>An upsert, because {@code (node_id, product_id)} is the natural primary key: the URL names
     * the row completely, so PUT is the honest verb whether or not it exists yet.
     *
     * @throws ProductScopeException if the product belongs to a different project from the node
     */
    public NodeProductDto upsertNodeProduct(long nodeId, long productId, long ownerId,
            NodeProductRequest request) {
        Node node = lookup.requireNode(nodeId, ownerId);
        guard.assertMutable(node.getNetwork().getId());

        Product product = lookup.requireProduct(productId, ownerId);
        long nodeProjectId = node.getNetwork().getProject().getId();
        long productProjectId = product.getProject().getId();
        if (nodeProjectId != productProjectId) {
            throw new ProductScopeException(productId, productProjectId, nodeProjectId);
        }

        NodeProduct nodeProduct = nodeProducts.findByNodeAndProduct(nodeId, productId)
                .orElseGet(() -> {
                    NodeProduct created = new NodeProduct(node, product);
                    node.addProduct(created);
                    return created;
                });
        mapper.replace(request, nodeProduct);
        return mapper.toDto(nodeProducts.save(nodeProduct));
    }

    public void deleteNodeProduct(long nodeId, long productId, long ownerId) {
        Node node = lookup.requireNode(nodeId, ownerId);
        guard.assertMutable(node.getNetwork().getId());
        nodeProducts.delete(requireNodeProduct(nodeId, productId));
    }

    // ----------------------------------------------------------------- helpers

    private Project requireProject(long projectId, long ownerId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No project with id " + projectId));
    }

    private NodeProduct requireNodeProduct(long nodeId, long productId) {
        return nodeProducts.findByNodeAndProduct(nodeId, productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Node %d has no parameters for product %d".formatted(nodeId, productId)));
    }
}
