package com.snrm.network;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Product} aggregate root.
 *
 * <p>Products are project-scoped, so they outlive any single network and are shared by every
 * configuration variant of a project.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByProjectIdOrderByNameAsc(Long projectId);

    /** Name resolution for the {@code node_products} import sheet, over {@code uq_product}. */
    Optional<Product> findByProjectIdAndName(Long projectId, String name);

    boolean existsByProjectIdAndName(Long projectId, String name);
}
