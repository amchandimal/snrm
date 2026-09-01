package com.snrm.network;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the ids in a request path to entities, refusing anything the caller does not own.
 *
 * <p>One component rather than a private helper in each service, because the three network services
 * ask the same two questions of every request — does this row exist, and is it the caller's — and
 * an ownership check that exists in two of three places is worse than none.
 *
 * <p><strong>Missing and not-yours are the same answer.</strong> Both raise
 * {@link EntityNotFoundException}, which the global handler renders as 404. A 403 would confirm
 * that an id exists, which is information the caller has not earned. With one research user in
 * Phase 1 the ownership branch never fires; it is written now so that multi-user hardening is a
 * change to {@code auth/CurrentUser} rather than an audit of every query.
 *
 * <p>Node and link lookups walk to the owner through their network's project. That is two lazy
 * proxies, hence two extra selects, on a path that already has to load the row — cheap enough for a
 * single-user research tool, and the alternative is denormalising the owner onto every table.
 */
@Component
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
public class NetworkLookup {

    private final NetworkRepository networks;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final ProductRepository products;

    NetworkLookup(NetworkRepository networks, NodeRepository nodes, LinkRepository links,
            ProductRepository products) {
        this.networks = networks;
        this.nodes = nodes;
        this.links = links;
        this.products = products;
    }

    /** @throws EntityNotFoundException if the network is missing or belongs to another user */
    public Network requireNetwork(long networkId, long ownerId) {
        Network network = networks.findById(networkId)
                .orElseThrow(() -> notFound("network", networkId));
        requireOwner(network, ownerId, "network", networkId);
        return network;
    }

    /** @throws EntityNotFoundException if the node is missing or belongs to another user */
    public Node requireNode(long nodeId, long ownerId) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> notFound("node", nodeId));
        requireOwner(node.getNetwork(), ownerId, "node", nodeId);
        return node;
    }

    /** @throws EntityNotFoundException if the link is missing or belongs to another user */
    public Link requireLink(long linkId, long ownerId) {
        Link link = links.findById(linkId).orElseThrow(() -> notFound("link", linkId));
        requireOwner(link.getNetwork(), ownerId, "link", linkId);
        return link;
    }

    /** @throws EntityNotFoundException if the product is missing or belongs to another user */
    public Product requireProduct(long productId, long ownerId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> notFound("product", productId));
        if (product.getProject().getOwnerId() != ownerId) {
            throw notFound("product", productId);
        }
        return product;
    }

    /**
     * A node that must belong to a specific network — the cross-network guard.
     *
     * @param role how the node is being used, for the message: {@code "source node"},
     *             {@code "target node"}, {@code "node"}
     * @throws EntityNotFoundException        if no such node exists at all
     * @throws CrossNetworkReferenceException if it exists but in a different network
     */
    public Node requireNodeIn(long networkId, long nodeId, String role) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> notFound("node", nodeId));
        long actualNetworkId = node.getNetwork().getId();
        if (actualNetworkId != networkId) {
            throw new CrossNetworkReferenceException(networkId, role, nodeId, actualNetworkId);
        }
        return node;
    }

    private static void requireOwner(Network network, long ownerId, String resource, long id) {
        if (network.getProject().getOwnerId() != ownerId) {
            throw notFound(resource, id);
        }
    }

    private static EntityNotFoundException notFound(String resource, long id) {
        return new EntityNotFoundException("No %s with id %d".formatted(resource, id));
    }
}
