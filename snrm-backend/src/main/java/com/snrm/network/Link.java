package com.snrm.network;

import com.snrm.common.AuditableEntity;
import com.snrm.common.DurationAmount;
import com.snrm.common.Rate;
import com.snrm.common.TimeUnit;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A directed transport arc between two nodes of the same network ({@code LINK}).
 *
 * <p>{@link #getLeadTime()} is a real duration in its own unit, not a period count, and
 * delays arrival of flow — modelled as pipeline inventory by the simulation engine.
 * The engine discretises it against the owning network's period and rounding policy.
 *
 * <p>Self-loops and duplicate {@code (source, target)} pairs are rejected: at the gesture level in
 * the editor, during import, and finally by {@code uq_link} and
 * {@code ck_link_no_self_loop} in the schema.
 */
@Entity
@Table(name = "link")
public class Link extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "network_id", nullable = false)
    private Network network;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_node_id", nullable = false)
    private Node sourceNode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_node_id", nullable = false)
    private Node targetNode;

    /**
     * Transit time as a duration in its own unit; 0 means flow arrives within the period it is
     * shipped. Replaced the period-counting {@code lead_time} column in V3.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value",
                    column = @Column(name = "lead_time_value", nullable = false)),
            @AttributeOverride(name = "unit",
                    column = @Column(name = "lead_time_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION)),
            @AttributeOverride(name = "seconds",
                    column = @Column(name = "lead_time_seconds", nullable = false))
    })
    private DurationAmount leadTime = DurationAmount.zero(TimeUnit.DAY);

    /**
     * Throughput ceiling over its own time unit; a null value means unconstrained.
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

    @Column(name = "unit_cost", nullable = false)
    private double unitCost;

    /** Independent per-period failure probability in [0,1]. */
    @Column(name = "failure_prob", nullable = false)
    private double failureProb;

    /**
     * Short annotation drawn beneath the arc's existing label — its declared lead time
     * (FR-30). Null is the only form of "no caption"; see {@link Node#getCaption()}.
     */
    @Column(name = "caption", length = Captions.MAX_LENGTH)
    private String caption;

    /** Whether the canvas draws {@link #getCaption()}; visible by default (FR-30). */
    @Column(name = "caption_visible", nullable = false)
    private boolean captionVisible = true;

    protected Link() {
        // for JPA
    }

    public Link(Network network, Node sourceNode, Node targetNode) {
        this.network = network;
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
    }

    /**
     * Keeps {@code lead_time_seconds} in step with its value and unit; see
     * {@link DurationAmount} on why the callback cannot live on the embeddable.
     */
    @PrePersist
    @PreUpdate
    private void refreshDerivedSeconds() {
        DurationAmount.refresh(leadTime);
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

    public Node getSourceNode() {
        return sourceNode;
    }

    public void setSourceNode(Node sourceNode) {
        this.sourceNode = sourceNode;
    }

    public Node getTargetNode() {
        return targetNode;
    }

    public void setTargetNode(Node targetNode) {
        this.targetNode = targetNode;
    }

    public DurationAmount getLeadTime() {
        return leadTime;
    }

    public void setLeadTime(DurationAmount leadTime) {
        this.leadTime = leadTime;
    }

    public Rate getCapacity() {
        return capacity;
    }

    public void setCapacity(Rate capacity) {
        this.capacity = capacity;
    }

    public double getUnitCost() {
        return unitCost;
    }

    public void setUnitCost(double unitCost) {
        this.unitCost = unitCost;
    }

    public double getFailureProb() {
        return failureProb;
    }

    public void setFailureProb(double failureProb) {
        this.failureProb = failureProb;
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
}
