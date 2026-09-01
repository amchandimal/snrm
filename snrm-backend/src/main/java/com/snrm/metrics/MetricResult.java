package com.snrm.metrics;

import com.snrm.common.AuditableEntity;
import com.snrm.common.TimeUnit;
import com.snrm.network.Network;
import com.snrm.simulation.SimulationRun;
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
import jakarta.persistence.Table;

/**
 * One computed metric value ({@code METRIC_RESULT}).
 *
 * <p>A deliberately narrow key–value table: {@code metric_code} is an opaque string, so adding a
 * metric to the suite is a new {@code MetricCalculator} bean and no schema change.
 *
 * <p>{@link #getRun()} is null for topological metrics — they are a property of the network, not of
 * any run, and are recomputed on save. Simulated metrics carry the run plus the 95%
 * confidence interval aggregated across replications; {@link #getCiLow()} and {@link #getCiHigh()}
 * stay null where an interval is not meaningful.
 *
 * <p>As with {@code simulation}, the entity is this module's persistence edge: the calculators
 * themselves see only the {@code NetworkGraph} snapshot and the replication traces, never JPA
 * types.
 */
@Entity
@Table(name = "metric_result")
public class MetricResult extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "network_id", nullable = false)
    private Network network;

    /** Null for topological metrics, which belong to the network rather than to a run. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id")
    private SimulationRun run;

    /** Metric identifier from the suite, e.g. {@code FILL_RATE}, {@code NODE_CRITICALITY}. */
    @Column(name = "metric_code", nullable = false, length = 40)
    private String metricCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false,
            columnDefinition = "ENUM('NETWORK','NODE','LINK')")
    private MetricScope scope = MetricScope.NETWORK;

    /** {@code node.id} or {@code link.id} per {@link #getScope()}; null when network-scoped. */
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "value", nullable = false)
    private double value;

    /** Lower bound of the 95% CI across replications; null for topological metrics. */
    @Column(name = "ci_low")
    private Double ciLow;

    /** Upper bound of the 95% CI across replications; null for topological metrics. */
    @Column(name = "ci_high")
    private Double ciHigh;

    /**
     * The time unit {@link #getValue()} is expressed over, or null where the metric has no time
     * dimension (FR-13).
     *
     * <p>Most of the suite is dimensionless — a fill rate, a centrality, a count — and
     * those rows leave this null, which is why V3 backfilled nothing here. It carries the unit for
     * the ones that are not: a time-to-recovery is a number whose meaning changes entirely with the
     * unit it is read in, and the comparison view puts variants side by side that need
     * not share a period. Storing the unit the calculator worked in lets a result be rendered in
     * any other one without the reader having to know the producing network's clock.
     *
     * <p>Deliberately not a {@code Rate}: the value is already the metric, and only its unit is in
     * question. A metric that genuinely is a rate should carry the time unit here and say so in its
     * calculator's Javadoc.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "display_unit", columnDefinition = TimeUnit.COLUMN_DEFINITION)
    private TimeUnit displayUnit;

    protected MetricResult() {
        // for JPA
    }

    public MetricResult(Network network, SimulationRun run, String metricCode,
            MetricScope scope, Long scopeId, double value) {
        this.network = network;
        this.run = run;
        this.metricCode = metricCode;
        this.scope = scope;
        this.scopeId = scopeId;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public Network getNetwork() {
        return network;
    }

    public SimulationRun getRun() {
        return run;
    }

    public String getMetricCode() {
        return metricCode;
    }

    public MetricScope getScope() {
        return scope;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public Double getCiLow() {
        return ciLow;
    }

    public void setCiLow(Double ciLow) {
        this.ciLow = ciLow;
    }

    public Double getCiHigh() {
        return ciHigh;
    }

    public void setCiHigh(Double ciHigh) {
        this.ciHigh = ciHigh;
    }

    /** The unit {@link #getValue()} is expressed over, or null if the metric is dimensionless. */
    public TimeUnit getDisplayUnit() {
        return displayUnit;
    }

    public void setDisplayUnit(TimeUnit displayUnit) {
        this.displayUnit = displayUnit;
    }
}
