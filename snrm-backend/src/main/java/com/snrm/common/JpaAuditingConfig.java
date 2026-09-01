package com.snrm.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables the Spring Data JPA auditing listener that populates
 * {@link AuditableEntity#getCreatedAt()} / {@link AuditableEntity#getUpdatedAt()}.
 *
 * <p>No {@code AuditorAware} bean is registered: the model records ownership on
 * {@code project.owner_id} only, so there are no {@code created_by} / {@code updated_by} columns
 * to fill. Add one here if the JWT work later introduces per-row attribution.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
