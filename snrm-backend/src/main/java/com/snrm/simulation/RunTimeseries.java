package com.snrm.simulation;

import com.snrm.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * Per-period aggregates of one run, averaged across replications
 * ({@code RUN_TIMESERIES}).
 *
 * <p>These rows are what let the performance curve and the resilience triangle be drawn without
 * recomputation: served vs. total demand gives the fill-rate curve, and the area between it and the
 * undisrupted baseline is {@code LOSS_AREA}.
 *
 * <p>Both curves are here. {@link #getBaselineServedDemand()} and {@link #getBaselineCost()} are the
 * undisrupted baseline replication set, added by {@code V6__run_timeseries_baseline.sql}
 * because the ER model predates that requirement and left the triangle undrawable from
 * stored data — see that migration for the argument. There is deliberately no baseline
 * {@code total_demand}: the paired replications share their demand realisations, so it would always
 * be a copy of {@link #getTotalDemand()}.
 *
 * <p>Keyed by {@code (run_id, period)} per the ER model, so it implements {@link Persistable}: with
 * an {@code @EmbeddedId} Spring Data would treat each instance as detached and issue a
 * {@code SELECT} before every {@code INSERT}, which matters when a run writes a full horizon in one
 * {@code saveAll}. A {@code null} {@code created_at} means the auditing listener has not run yet,
 * which is exactly "not yet persisted".
 */
@Entity
@Table(name = "run_timeseries")
public class RunTimeseries extends AuditableEntity implements Persistable<RunTimeseriesId> {

    @EmbeddedId
    private RunTimeseriesId id;

    @MapsId("runId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private SimulationRun run;

    @Column(name = "served_demand", nullable = false)
    private double servedDemand;

    @Column(name = "total_demand", nullable = false)
    private double totalDemand;

    @Column(name = "cost", nullable = false)
    private double cost;

    /** Served demand in the same period of the undisrupted baseline set (V6). */
    @Column(name = "baseline_served_demand", nullable = false)
    private double baselineServedDemand;

    /** Cost in the same period of the undisrupted baseline set (V6). */
    @Column(name = "baseline_cost", nullable = false)
    private double baselineCost;

    /**
     * On-hand stock across the whole network at the end of the period, mean across the disrupted
     * replications ({@code V9__element_timeseries.sql}).
     *
     * <p>Already on every {@link PeriodTrace} and, until V9, thrown away. It is the total the
     * per-element {@code on_hand} column sums to, so a client can draw the aggregate inventory curve
     * without pulling the element series — and a reader can check the element series against it.
     */
    @Column(name = "ending_inventory", nullable = false)
    private double endingInventory;

    /**
     * Material in transit or in processing dwell across the network at the end of the period, mean
     * across the disrupted replications ({@code V9__element_timeseries.sql}).
     *
     * <p>Pipeline inventory: shipped and paid for but not yet anywhere. On a network with
     * lead times this is the quantity a static table cannot show and a playback view exists to draw.
     */
    @Column(name = "in_pipeline", nullable = false)
    private double inPipeline;

    protected RunTimeseries() {
        // for JPA
    }

    public RunTimeseries(SimulationRun run, int period) {
        this.run = run;
        this.id = new RunTimeseriesId(null, period);
    }

    @Override
    public RunTimeseriesId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }

    public SimulationRun getRun() {
        return run;
    }

    public int getPeriod() {
        return id.getPeriod();
    }

    public double getServedDemand() {
        return servedDemand;
    }

    public void setServedDemand(double servedDemand) {
        this.servedDemand = servedDemand;
    }

    public double getTotalDemand() {
        return totalDemand;
    }

    public void setTotalDemand(double totalDemand) {
        this.totalDemand = totalDemand;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public double getBaselineServedDemand() {
        return baselineServedDemand;
    }

    public void setBaselineServedDemand(double baselineServedDemand) {
        this.baselineServedDemand = baselineServedDemand;
    }

    public double getBaselineCost() {
        return baselineCost;
    }

    public void setBaselineCost(double baselineCost) {
        this.baselineCost = baselineCost;
    }

    public double getEndingInventory() {
        return endingInventory;
    }

    public void setEndingInventory(double endingInventory) {
        this.endingInventory = endingInventory;
    }

    public double getInPipeline() {
        return inPipeline;
    }

    public void setInPipeline(double inPipeline) {
        this.inPipeline = inPipeline;
    }
}
