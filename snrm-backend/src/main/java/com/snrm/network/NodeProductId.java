package com.snrm.network;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link NodeProduct}: the natural {@code (node_id, product_id)} pair.
 *
 * <p>The ER model gives {@code NODE_PRODUCT} no surrogate identifier, so the mapping keeps
 * the natural key rather than inventing one. Both components are filled by {@code @MapsId} from the
 * owning associations.
 */
@Embeddable
public class NodeProductId implements Serializable {

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "product_id")
    private Long productId;

    protected NodeProductId() {
        // for JPA
    }

    public NodeProductId(Long nodeId, Long productId) {
        this.nodeId = nodeId;
        this.productId = productId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public Long getProductId() {
        return productId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NodeProductId that)) {
            return false;
        }
        return Objects.equals(nodeId, that.nodeId) && Objects.equals(productId, that.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, productId);
    }

    @Override
    public String toString() {
        return "NodeProductId[node=" + nodeId + ", product=" + productId + "]";
    }
}
