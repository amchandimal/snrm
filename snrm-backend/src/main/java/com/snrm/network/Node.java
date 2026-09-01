package com.snrm.network;

import com.snrm.common.AuditableEntity;
import com.snrm.common.DurationAmount;
import com.snrm.common.Rate;
import com.snrm.common.TimeUnit;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A facility in the network — supplier, plant, DC or customer ({@code NODE}).
 *
 * <p>Names are unique within a network ({@code uq_node}), which is what lets the CSV/XLSX import
 * resolve {@code links.source} / {@code links.target} by name.
 *
 * <p>{@link #getPosX()} / {@link #getPosY()} are canvas coordinates, not geography: the editor
 * writes them on drop and on drag so a hand-made layout survives a reload, and auto-layout only
 * fills them in when they are absent. {@link #getLat()} / {@link #getLng()} are the
 * geographic pair and are independent of them.
 *
 * <p>{@link #getRegion()} is the tag that {@code REGION}-scoped disruption events resolve through,
 * which is how correlated geographic disruptions are expressed.
 *
 * <p>{@link #getCaption()} and {@link #isCaptionVisible()} are the annotation of FR-30 — an ordinary
 * attribute in every respect, including that writing one is refused on a frozen network exactly as
 * any other edit is - no exemption was made for captions. No engine reads either.
 */
@Entity
@Table(name = "node")
public class Node extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "network_id", nullable = false)
    private Network network;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /**
     * Pinned to the migration's MySQL {@code ENUM} so {@code ddl-auto=validate} accepts the column:
     * Hibernate maps a {@code STRING} enum to {@code varchar}, which does not match what MySQL
     * reports for an {@code ENUM}. Keep the literals identical to {@code V2__domain.sql}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false,
            columnDefinition = "ENUM('SUPPLIER','PLANT','DC','CUSTOMER')")
    private NodeType type;

    /**
     * Throughput ceiling, stated over its own time unit; a null value means unconstrained.
     * Replaced {@code capacity_per_period} in V3.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "capacity_value")),
            @AttributeOverride(name = "timeUnit",
                    column = @Column(name = "capacity_time_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION))
    })
    private Rate capacity = Rate.unconstrained(TimeUnit.DAY);

    /**
     * Time material spends being handled at this node before it can leave — dwell at a DC, cycle
     * time at a plant. Zero by default, which is the behaviour every network had
     * before V3 introduced the column, so the backfill left existing nodes unchanged.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value",
                    column = @Column(name = "processing_time_value", nullable = false)),
            @AttributeOverride(name = "unit",
                    column = @Column(name = "processing_time_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION)),
            @AttributeOverride(name = "seconds",
                    column = @Column(name = "processing_time_seconds", nullable = false))
    })
    private DurationAmount processingTime = DurationAmount.zero(TimeUnit.DAY);

    @Column(name = "fixed_cost", nullable = false)
    private double fixedCost;

    @Column(name = "var_cost", nullable = false)
    private double varCost;

    /** Independent per-period failure probability in [0,1]. */
    @Column(name = "failure_prob", nullable = false)
    private double failureProb;

    /** Geographic tag; the resolution target of {@code REGION} disruption events. */
    @Column(name = "region", length = 60)
    private String region;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lng")
    private Double lng;

    /** Editor canvas x-coordinate; persisted so manual layouts survive reload. */
    @Column(name = "pos_x")
    private Double posX;

    /** Editor canvas y-coordinate; persisted so manual layouts survive reload. */
    @Column(name = "pos_y")
    private Double posY;

    /**
     * Short annotation drawn beneath the node's name in smaller, quieter type (FR-30).
     *
     * <p>Null is the only form of "no caption": a blank string is normalised to null on every write
     * path ({@link Captions}), so nothing downstream has to decide whether {@code ""} means an empty
     * caption or an absent one.
     */
    @Column(name = "caption", length = Captions.MAX_LENGTH)
    private String caption;

    /**
     * Whether the canvas draws {@link #getCaption()} (FR-30).
     *
     * <p>Visible by default, and an <em>empty caption draws nothing whatever this says</em> — so the
     * flag is a control over annotation that exists rather than a way to reserve space, and typing a
     * caption shows one without a second gesture. The V10 column default and the omission
     * rules are the same statement in three places.
     */
    @Column(name = "caption_visible", nullable = false)
    private boolean captionVisible = true;

    @OneToMany(mappedBy = "node", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NodeProduct> products = new ArrayList<>();

    protected Node() {
        // for JPA
    }

    public Node(Network network, String name, NodeType type) {
        this.network = network;
        this.name = name;
        this.type = type;
    }

    /**
     * Keeps {@code processing_time_seconds} in step with its value and unit; see
     * {@link DurationAmount} on why the callback cannot live on the embeddable.
     */
    @PrePersist
    @PreUpdate
    private void refreshDerivedSeconds() {
        DurationAmount.refresh(processingTime);
    }

    /** Adds a per-product row and keeps both sides of the association consistent. */
    public void addProduct(NodeProduct nodeProduct) {
        products.add(nodeProduct);
        nodeProduct.setNode(this);
    }

    public Long getId() {
        return id;
    }

    public Network getNetwork() {
        return network;
    }

    void setNetwork(Network network) {
        this.network = network;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public Rate getCapacity() {
        return capacity;
    }

    public void setCapacity(Rate capacity) {
        this.capacity = capacity;
    }

    public DurationAmount getProcessingTime() {
        return processingTime;
    }

    public void setProcessingTime(DurationAmount processingTime) {
        this.processingTime = processingTime;
    }

    public double getFixedCost() {
        return fixedCost;
    }

    public void setFixedCost(double fixedCost) {
        this.fixedCost = fixedCost;
    }

    public double getVarCost() {
        return varCost;
    }

    public void setVarCost(double varCost) {
        this.varCost = varCost;
    }

    public double getFailureProb() {
        return failureProb;
    }

    public void setFailureProb(double failureProb) {
        this.failureProb = failureProb;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public Double getPosX() {
        return posX;
    }

    public void setPosX(Double posX) {
        this.posX = posX;
    }

    public Double getPosY() {
        return posY;
    }

    public void setPosY(Double posY) {
        this.posY = posY;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public boolean isCaptionVisible() {
        return captionVisible;
    }

    public void setCaptionVisible(boolean captionVisible) {
        this.captionVisible = captionVisible;
    }

    public List<NodeProduct> getProducts() {
        return products;
    }
}
