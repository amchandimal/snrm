package com.snrm.network;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Node}, a child of the {@link Network} aggregate.
 *
 * <p>Addressed directly because the editor and the bulk canvas endpoints work node-by-node;
 * the snapshot builder loads the whole network through
 * {@link NetworkRepository} instead.
 */
public interface NodeRepository extends JpaRepository<Node, Long> {

    List<Node> findByNetworkId(Long networkId);

    /** Over {@code ix_node_network_type}; used by the network-level import checks. */
    List<Node> findByNetworkIdAndType(Long networkId, NodeType type);

    /** Over {@code ix_node_network_region}; resolves {@code REGION}-scoped disruptions. */
    List<Node> findByNetworkIdAndRegion(Long networkId, String region);

    /**
     * How many nodes of this network carry a region tag — the REGION half of disruption-event target
     * resolution, and the count each row of the region catalogue shows.
     */
    long countByNetworkIdAndRegion(Long networkId, String region);

    /**
     * Every region tag in use in this network, alphabetically — the row picker of the scenario
     * builder's timeline.
     *
     * <p>Distinct values of a column rather than rows, because a region is a tag and not an entity:
     * there is nothing else to return about it. Untagged nodes are excluded, so the catalogue lists
     * what a REGION event could actually be pointed at.
     */
    @Query("SELECT DISTINCT n.region FROM Node n "
            + "WHERE n.network.id = :networkId AND n.region IS NOT NULL AND n.region <> '' "
            + "ORDER BY n.region ASC")
    List<String> findDistinctRegions(@Param("networkId") Long networkId);

    /** Name resolution for the CSV/XLSX import, over {@code uq_node}. */
    Optional<Node> findByNetworkIdAndName(Long networkId, String name);

    boolean existsByNetworkIdAndName(Long networkId, String name);

    long countByNetworkIdAndType(Long networkId, NodeType type);
}
