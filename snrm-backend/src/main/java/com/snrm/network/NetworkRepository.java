package com.snrm.network;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Network} aggregate root — nodes and links included.
 */
public interface NetworkRepository extends JpaRepository<Network, Long> {

    List<Network> findByProjectIdOrderByNameAscVersionAsc(Long projectId);

    /**
     * The network the comparison view measures the others against.
     *
     * <p>"At most one baseline per project" is a service-level invariant: the model gives
     * {@code is_baseline} as a plain flag, and MySQL has no partial unique index to express it.
     * Two baselines in one project would make this method throw rather than pick one.
     */
    Optional<Network> findByProjectIdAndBaselineIsTrue(Long projectId);

    /**
     * Highest variant number in use under {@code (project, name)}; a fork takes the next one and
     * so keeps {@code uq_network} satisfied.
     */
    @Query("""
            select coalesce(max(n.version), 0) from Network n
            where n.project.id = :projectId and n.name = :name""")
    int findMaxVersion(@Param("projectId") Long projectId, @Param("name") String name);
}
