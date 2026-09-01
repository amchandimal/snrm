package com.snrm.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Auditing timestamps shared by every persisted entity of the model.
 *
 * <p>Deliberately carries no identifier: the model mixes surrogate-key entities
 * (most tables) with natural composite-key ones ({@code node_product}, {@code run_timeseries}),
 * so each entity declares its own {@code @Id}. Values are supplied by Spring Data JPA auditing
 * (see {@link JpaAuditingConfig}); the columns also carry a {@code DEFAULT CURRENT_TIMESTAMP(6)}
 * in the migration so hand-written SQL fixtures insert cleanly.
 *
 * <p>Timestamps are {@link Instant} and the JDBC session runs in UTC
 * ({@code hibernate.jdbc.time_zone}), so a run's timeline is reproducible across machines and
 * DST boundaries.
 *
 * <p>The explicit {@code columnDefinition} is not decoration: {@code ddl-auto=validate} compares
 * the mapped SQL type against what MySQL reports, and Hibernate maps {@code Instant} to a
 * timestamp type code that does not match MySQL's {@code DATETIME}. Pinning the definition to the
 * exact type used in {@code V2__domain.sql} keeps startup validation deterministic. Do not remove it.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
