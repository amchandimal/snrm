package com.snrm.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Project} aggregate root.
 *
 * <p>Networks, products, scenarios and variants are separate aggregates keyed by
 * {@code project_id} rather than collections on this root: each is large enough that loading it
 * with the project would be wasteful, and each has its own lifecycle.
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwnerIdOrderByNameAsc(Long ownerId);

    Optional<Project> findByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndName(Long ownerId, String name);
}
