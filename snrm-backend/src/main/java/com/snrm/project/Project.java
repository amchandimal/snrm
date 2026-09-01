package com.snrm.project;

import com.snrm.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Top-level container for one modelling exercise: its networks, products, disruption scenarios and
 * configuration variants ({@code PROJECT}).
 *
 * <p>{@code owner_id} carries no foreign key: Phase 1 is a single research user and there is no
 * user table yet (auth is the HTTP Basic stand-in noted in the README until the JWT flow
 * lands). It is modelled now so project-level ownership checks are a filter rather than
 * a migration when multi-user hardening arrives.
 */
@Entity
@Table(name = "project")
public class Project extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    protected Project() {
        // for JPA
    }

    public Project(String name, Long ownerId) {
        this.name = name;
        this.ownerId = ownerId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }
}
