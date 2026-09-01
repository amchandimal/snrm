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
 * What one link carried in one period of one run, averaged across replications
 * (FR-18 — {@code V9__element_timeseries.sql}).
 *
 * <p>The arc half of the per-element series; {@link RunNodeTimeseries} carries the node half and
 * states the conventions both share — replication means, the nullable columns, why the element id is
 * a plain column, and why these rows carry {@code created_at} alone.
 *
 * <p><strong>{@link #getFlow()} is what was <em>dispatched</em> onto the arc, not what arrived.</strong>
 * An arc with a lead time lands its flow at the target in a later period, so a ribbon drawn
 * from this column shows material leaving now and the target's {@code arrivals} shows it landing
 * later. That one-period offset is the single mechanic a playback view is most likely to render
 * wrongly and a viewer most likely to call a bug — {@code samples/four-echelon-playback/README.md}
 * §8.3 works it through.
 *
 * <p><strong>{@link #getUtilisation()} is nullable in two different situations, and neither is a
 * zero.</strong> An arc with no declared capacity has no fraction to be at — "an unconstrained
 * element cannot be partly disrupted" ({@code FlowAllocator.adjusted}) — and an arc an event has
 * taken to zero available capacity is dark rather than idle, where {@code 0/0} is undefined. A link
 * that simply carried nothing through a period of full availability is a true {@code 0.0}, and the
 * two must render differently.
 */
@Entity
@Table(name = "run_link_timeseries")
@EntityListeners(AuditingEntityListener.class)
public class RunLinkTimeseries implements Persistable<RunLinkTimeseriesId> {

    @EmbeddedId
    private RunLinkTimeseriesId id;

    @MapsId("runId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private SimulationRun run;

    /** Dispatched onto the arc this period — see the class note on what that is not. */
    @Column(name = "flow", nullable = false)
    private double flow;

    /** Flow over the capacity actually available; null when uncapped or fully out (see class note). */
    @Column(name = "utilisation")
    private Double utilisation;

    /** The period's availability multiplier: scenario events × random outages. */
    @Column(name = "availability", nullable = false)
    private double availability;

    /** {@link #getFlow()} in the undisrupted baseline set (V9). */
    @Column(name = "baseline_flow", nullable = false)
    private double baselineFlow;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected RunLinkTimeseries() {
        // for JPA
    }

    /** @param linkId the link's persistent id; {@code runId} is filled by {@code @MapsId} */
    public RunLinkTimeseries(SimulationRun run, long linkId, int period) {
        this.run = run;
        this.id = new RunLinkTimeseriesId(null, linkId, period);
    }

    @Override
    public RunLinkTimeseriesId getId() {
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

    public long getLinkId() {
        return id.getLinkId();
    }

    public int getPeriod() {
        return id.getPeriod();
    }

    public double getFlow() {
        return flow;
    }

    public void setFlow(double flow) {
        this.flow = flow;
    }

    /** Null where the arc is uncapped, or where an outage left it no capacity — never read as 0. */
    public Double getUtilisation() {
        return utilisation;
    }

    public void setUtilisation(Double utilisation) {
        this.utilisation = utilisation;
    }

    public double getAvailability() {
        return availability;
    }

    public void setAvailability(double availability) {
        this.availability = availability;
    }

    public double getBaselineFlow() {
        return baselineFlow;
    }

    public void setBaselineFlow(double baselineFlow) {
        this.baselineFlow = baselineFlow;
    }
}
