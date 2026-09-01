package com.snrm.network;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link ConfigurationVariant} aggregate root.
 */
public interface ConfigurationVariantRepository extends JpaRepository<ConfigurationVariant, Long> {

    List<ConfigurationVariant> findByProjectIdOrderByIdDesc(Long projectId);

    /** The variant record of a materialised network, if it is not a baseline (ER 1:1). */
    Optional<ConfigurationVariant> findByNetworkId(Long networkId);

    /** Everything forked from a given network — the provenance tree of a baseline. */
    List<ConfigurationVariant> findByBaseNetworkId(Long baseNetworkId);

    List<ConfigurationVariant> findByProjectIdAndGeneratedBy(Long projectId, VariantOrigin generatedBy);
}
