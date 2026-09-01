package com.snrm.network;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link NodeProduct}, a child of the {@link Node} aggregate.
 *
 * <p>Queries reach through the composite key explicitly rather than relying on derived names, so
 * the access path stays legible.
 */
public interface NodeProductRepository extends JpaRepository<NodeProduct, NodeProductId> {

    @Query("select np from NodeProduct np where np.id.nodeId = :nodeId")
    List<NodeProduct> findByNodeId(@Param("nodeId") Long nodeId);

    @Query("select np from NodeProduct np where np.id.nodeId = :nodeId and np.id.productId = :productId")
    Optional<NodeProduct> findByNodeAndProduct(@Param("nodeId") Long nodeId,
            @Param("productId") Long productId);

    /** Every per-product row of a network, for the snapshot builder. */
    @Query("select np from NodeProduct np join np.node n where n.network.id = :networkId")
    List<NodeProduct> findByNetworkId(@Param("networkId") Long networkId);
}
