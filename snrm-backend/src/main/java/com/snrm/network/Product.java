package com.snrm.network;

import com.snrm.common.AuditableEntity;
import com.snrm.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A product flowing through the network ({@code PRODUCT}).
 *
 * <p>Scoped to the <em>project</em>, not to a network: a configuration variant is a different
 * network over the same product catalogue, so forking a variant reuses these rows rather than
 * copying them.
 *
 * <p>{@link #getUnitValue()} weights unserved demand when service loss is expressed in monetary
 * terms. Import creates a single default product when the
 * {@code products} sheet is absent.
 */
@Entity
@Table(name = "product")
public class Product extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "unit_value", nullable = false)
    private double unitValue;

    protected Product() {
        // for JPA
    }

    public Product(Project project, String name, double unitValue) {
        this.project = project;
        this.name = name;
        this.unitValue = unitValue;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getUnitValue() {
        return unitValue;
    }

    public void setUnitValue(double unitValue) {
        this.unitValue = unitValue;
    }
}
