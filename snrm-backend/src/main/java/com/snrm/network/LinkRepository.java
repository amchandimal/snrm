package com.snrm.network;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Link}, a child of the {@link Network} aggregate.
 */
public interface LinkRepository extends JpaRepository<Link, Long> {

    List<Link> findByNetworkId(Long networkId);

    /** Duplicate-pair check for the editor gesture and for import, over {@code uq_link}. */
    Optional<Link> findByNetworkIdAndSourceNodeIdAndTargetNodeId(Long networkId, Long sourceNodeId,
            Long targetNodeId);

    boolean existsByNetworkIdAndSourceNodeIdAndTargetNodeId(Long networkId, Long sourceNodeId,
            Long targetNodeId);

    /** Outgoing adjacency of a node; also the dependency list shown before a delete. */
    List<Link> findBySourceNodeId(Long sourceNodeId);

    /** Incoming adjacency of a node. */
    List<Link> findByTargetNodeId(Long targetNodeId);

    /**
     * Whether a link id names an arc of this network — the LINK half of disruption-event target
     * resolution.
     *
     * <p>An existence check rather than a fetch: the caller needs to know the reference resolves,
     * not what it resolves to, and {@code target_id} carries no foreign key that could have
     * answered it.
     */
    boolean existsByIdAndNetworkId(Long id, Long networkId);
}
