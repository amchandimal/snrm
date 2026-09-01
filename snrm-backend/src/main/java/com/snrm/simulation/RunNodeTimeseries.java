package com.snrm.simulation;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * What one node did in one period of one run, averaged across replications
 * (FR-18 — {@code V9__element_timeseries.sql}).
 *
 * <p>{@link RunTimeseries} answers "how did the network perform in period t". These rows answer the
 * question a playback view asks instead: <em>which element</em> held the stock, moved the material,
 * went dark. The aggregate curve cannot be disaggregated back, so the series is recorded rather than
 * derived — see the migration header for why re-running or shipping raw traces were both rejected.
 *
 * <p>Every {@code double} here is a <strong>mean over the disrupted replications</strong>, the same
 * convention {@code RUN_TIMESERIES} follows. {@link #getBaselineOnHand()} and
 * {@link #getBaselineServed()} are the same period of the undisrupted baseline set —
 * or, on the baseline run of FR-17 where the pairing was skipped, a copy of this run's own series,
 * which is definitionally true rather than a stand-in ({@link ElementAccumulator#mean()}).
 *
 * <p><strong>{@link #getInboundLead()} is nullable, and null is a claim.</strong> It is the
 * dispatch-weighted transport lead of everything sent toward this node in the period; a period in
 * which nothing was dispatched toward it has no such mean, and a 0 there would say material arrived
 * instantly. Absent renders absent, never zero.
 *
 * <h2>Two departures from {@link RunTimeseries}, both deliberate</h2>
 *
 * <p><strong>{@code node_id} is a plain column, not an association.</strong> A results row is a
 * statement about a run: it must outlive nothing and be blocked by nothing. Mapping the node would
 * make a completed run's series a reason a node cannot be deleted, which is a rule about the editor
 * (decided in {@code NodeService}) and not one for a results table to invent. A run freezes its
 * network anyway, and {@code fk_node_ts_run} takes these rows with the run.
 *
 * <p><strong>It carries {@code created_at} and no {@code updated_at}, so it does not extend
 * {@code AuditableEntity}.</strong> These rows are written once, inside {@code persistResults}, and
 * a completed run is immutable — there is no second write for an {@code updated_at} to
 * record, so the column could only ever equal {@code created_at}. That is one dead timestamp per
 * (run × node × period): on the 1,000-node networks of FR-04 with a 52-period horizon it is 52,000
 * of them per run. The {@link Persistable} contract needs only the one, and it is unchanged —
 * a null {@code created_at} means the auditing listener has not run yet, which is exactly "not yet
 * persisted", and is what stops Spring Data issuing a {@code SELECT} before every {@code INSERT} of
 * a horizon-long {@code saveAll}.
 */
@Entity
@Table(name = "run_node_timeseries")
@EntityListeners(AuditingEntityListener.class)
public class RunNodeTimeseries implements Persistable<RunNodeTimeseriesId> {

    @EmbeddedId
    private RunNodeTimeseriesId id;

    @MapsId("runId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private SimulationRun run;

    /** End-of-period stock, after this period's arrivals and dispatches. */
    @Column(name = "on_hand", nullable = false)
    private double onHand;

    /** Material on its way here at period end — inbound shipments plus this node's own dwell. */
    @Column(name = "in_transit", nullable = false)
    private double inTransit;

    /** What the pipeline delivered here this period, captured before the slot is cleared. */
    @Column(name = "arrivals", nullable = false)
    private double arrivals;

    /** Demand met at this node; zero anywhere but a {@code CUSTOMER}. */
    @Column(name = "served", nullable = false)
    private double served;

    @Column(name = "unserved", nullable = false)
    private double unserved;

    /**
     * Flow across this node's own capacity arc — production at a supply origin, pass-through
     * elsewhere. Material shipped out of stock is <em>not</em> here: stock enters the flow at the
     * node's dispatch vertex and never crosses the capacity arc ({@code FlowAllocator}).
     */
    @Column(name = "throughput", nullable = false)
    private double throughput;

    /** The period's availability multiplier: scenario events × random outages. */
    @Column(name = "availability", nullable = false)
    private double availability;

    /** Dispatch-weighted inbound transport lead in periods; null when nothing was dispatched here. */
    @Column(name = "inbound_lead")
    private Double inboundLead;

    /** {@link #getOnHand()} in the undisrupted baseline set (V9). */
    @Column(name = "baseline_on_hand", nullable = false)
    private double baselineOnHand;

    /** {@link #getServed()} in the undisrupted baseline set (V9). */
    @Column(name = "baseline_served", nullable = false)
    private double baselineServed;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected RunNodeTimeseries() {
        // for JPA
    }

    /** @param nodeId the node's persistent id; {@code runId} is filled by {@code @MapsId} */
    public RunNodeTimeseries(SimulationRun run, long nodeId, int period) {
        this.run = run;
        this.id = new RunNodeTimeseriesId(null, nodeId, period);
    }

    @Override
    public RunNodeTimeseriesId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return createdAt == null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public SimulationRun getRun() {
        return run;
    }

    public long getNodeId() {
        return id.getNodeId();
    }

    public int getPeriod() {
        return id.getPeriod();
    }

    public double getOnHand() {
        return onHand;
    }

    public void setOnHand(double onHand) {
        this.onHand = onHand;
    }

    public double getInTransit() {
        return inTransit;
    }

    public void setInTransit(double inTransit) {
        this.inTransit = inTransit;
    }

    public double getArrivals() {
        return arrivals;
    }

    public void setArrivals(double arrivals) {
        this.arrivals = arrivals;
    }

    public double getServed() {
        return served;
    }

    public void setServed(double served) {
        this.served = served;
    }

    public double getUnserved() {
        return unserved;
    }

    public void setUnserved(double unserved) {
        this.unserved = unserved;
    }

    public double getThroughput() {
        return throughput;
    }

    public void setThroughput(double throughput) {
        this.throughput = throughput;
    }

    public double getAvailability() {
        return availability;
    }

    public void setAvailability(double availability) {
        this.availability = availability;
    }

    /** Null when nothing was dispatched toward this node in the period — never read it as 0. */
    public Double getInboundLead() {
        return inboundLead;
    }

    public void setInboundLead(Double inboundLead) {
        this.inboundLead = inboundLead;
    }

    public double getBaselineOnHand() {
        return baselineOnHand;
    }

    public void setBaselineOnHand(double baselineOnHand) {
        this.baselineOnHand = baselineOnHand;
    }

    public double getBaselineServed() {
        return baselineServed;
    }

    public void setBaselineServed(double baselineServed) {
        this.baselineServed = baselineServed;
    }
}
