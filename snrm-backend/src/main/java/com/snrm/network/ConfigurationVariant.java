package com.snrm.network;

import com.snrm.common.AuditableEntity;
import com.snrm.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * The provenance record linking a derived network back to the network it was forked from
 * ({@code CONFIGURATION_VARIANT}).
 *
 * <p>The ER model's {@code CONFIGURATION_VARIANT ||--|| NETWORK : "materialises as"} edge is
 * {@link #getNetwork()}, held unique so a network materialises at most one variant record;
 * {@link #getBaseNetwork()} is the network it was derived from. A baseline network has no variant
 * row at all.
 *
 * <p><strong>Why this lives in {@code network} and not {@code configuration}.</strong> The
 * {@code configuration} package is reserved for the Phase 2 engine, and that engine depends
 * only on the immutable {@code NetworkGraph} snapshot — never on JPA entities or repositories.
 * Putting a JPA entity there would break that isolation on day one. The variant is also created on
 * the Phase 1 path (an editor fork), which is owned by this module.
 *
 * <p>{@link #getLeverChangesJson()} is the structured diff from the base network — added backup
 * supplier, +20% capacity at node 12 — which is what makes lever-level attribution of metric
 * improvements possible. It is kept as opaque JSON text at the persistence layer;
 * parsing belongs to the module that owns the lever vocabulary.
 */
@Entity
@Table(name = "configuration_variant")
public class ConfigurationVariant extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** The network this variant was derived from. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "base_network_id", nullable = false)
    private Network baseNetwork;

    /** The network this variant materialises as (ER 1:1, enforced by {@code uq_variant_network}). */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "network_id", nullable = false, unique = true)
    private Network network;

    @Enumerated(EnumType.STRING)
    @Column(name = "generated_by", nullable = false,
            columnDefinition = "ENUM('MANUAL','SEARCH')")
    private VariantOrigin generatedBy;

    /**
     * Structured diff from {@link #getBaseNetwork()}. Mapped as {@code String} against a MySQL
     * {@code JSON} column: the driver parses and rejects malformed JSON on write, and the
     * persistence layer stays agnostic of the lever schema.
     */
    @Column(name = "lever_changes_json", columnDefinition = "JSON")
    private String leverChangesJson;

    protected ConfigurationVariant() {
        // for JPA
    }

    public ConfigurationVariant(Project project, Network baseNetwork, Network network,
            VariantOrigin generatedBy, String leverChangesJson) {
        this.project = project;
        this.baseNetwork = baseNetwork;
        this.network = network;
        this.generatedBy = generatedBy;
        this.leverChangesJson = leverChangesJson;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public Network getBaseNetwork() {
        return baseNetwork;
    }

    public Network getNetwork() {
        return network;
    }

    public VariantOrigin getGeneratedBy() {
        return generatedBy;
    }

    public String getLeverChangesJson() {
        return leverChangesJson;
    }

    public void setLeverChangesJson(String leverChangesJson) {
        this.leverChangesJson = leverChangesJson;
    }
}
