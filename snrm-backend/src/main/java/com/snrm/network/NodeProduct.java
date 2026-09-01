package com.snrm.network;

import com.snrm.common.AuditableEntity;
import com.snrm.common.Rate;
import com.snrm.common.TimeUnit;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * Per-node, per-product parameters: demand, inventory and holding cost
 * ({@code NODE_PRODUCT}, {@code node_products} import sheet).
 *
 * <p>{@link #getDemand()} is meaningful on {@link NodeType#CUSTOMER} nodes; the inventory fields on
 * supply-side nodes, where stock acts as an additional supply source in the period after it is
 * stocked.
 *
 * <p>Demand and holding cost are {@link Rate}s over their own time unit rather than per-period
 * quantities (FR-13): a demand of 120 a week means the same thing whether the network
 * steps in days or in weeks. Initial inventory and safety stock stay plain quantities — they are
 * stock levels, not flows, and have no time dimension to state.
 *
 * <p>Keyed by the natural {@code (node_id, product_id)} pair per the ER model, so it implements
 * {@link Persistable}: with an {@code @EmbeddedId} Spring Data would otherwise treat every instance
 * as detached and issue a {@code SELECT} before each {@code INSERT}. A {@code null}
 * {@code created_at} means the auditing listener has not run yet, which is exactly "not yet persisted".
 */
@Entity
@Table(name = "node_product")
public class NodeProduct extends AuditableEntity implements Persistable<NodeProductId> {

    @EmbeddedId
    private NodeProductId id = new NodeProductId(null, null);

    @MapsId("nodeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id", nullable = false)
    private Node node;

    @MapsId("productId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Demand over its own time unit; replaced {@code demand_per_period} in V3. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value",
                    column = @Column(name = "demand_value", nullable = false)),
            @AttributeOverride(name = "timeUnit",
                    column = @Column(name = "demand_time_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION))
    })
    private Rate demand = Rate.zero(TimeUnit.DAY);

    @Column(name = "initial_inventory", nullable = false)
    private double initialInventory;

    @Column(name = "safety_stock", nullable = false)
    private double safetyStock;

    /**
     * Cost of carrying one unit for one of {@code holding_cost_time_unit}; replaced the per-period
     * {@code holding_cost} in V3.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value",
                    column = @Column(name = "holding_cost_value", nullable = false)),
            @AttributeOverride(name = "timeUnit",
                    column = @Column(name = "holding_cost_time_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION))
    })
    private Rate holdingCost = Rate.zero(TimeUnit.DAY);

    protected NodeProduct() {
        // for JPA
    }

    public NodeProduct(Node node, Product product) {
        this.node = node;
        this.product = product;
    }

    @Override
    public NodeProductId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }

    public Node getNode() {
        return node;
    }

    void setNode(Node node) {
        this.node = node;
    }

    public Product getProduct() {
        return product;
    }

    public Rate getDemand() {
        return demand;
    }

    public void setDemand(Rate demand) {
        this.demand = demand;
    }

    public double getInitialInventory() {
        return initialInventory;
    }

    public void setInitialInventory(double initialInventory) {
        this.initialInventory = initialInventory;
    }

    public double getSafetyStock() {
        return safetyStock;
    }

    public void setSafetyStock(double safetyStock) {
        this.safetyStock = safetyStock;
    }

    public Rate getHoldingCost() {
        return holdingCost;
    }

    public void setHoldingCost(Rate holdingCost) {
        this.holdingCost = holdingCost;
    }
}
