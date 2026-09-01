package com.snrm.metrics.simulated;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import com.snrm.simulation.PeriodTrace;
import com.snrm.simulation.ReplicationTrace;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code AVG_INVENTORY} — the inventory/absorption family (FR-19).
 *
 * <blockquote>"{@code AVG_INVENTORY}, {@code AVG_PIPELINE} — Mean end-of-period total on-hand stock,
 * and mean in-transit (WIP), across the horizon — per replication, then mean and 95% CI. Both
 * {@code NEUTRAL}: leaner versus more buffered is the trade-off under study, not a ranking."
 * </blockquote>
 *
 * <pre>
 *   AVG_INVENTORY(replication) = ( Σ_t endingInventory(t) ) / H
 * </pre>
 *
 * <p><strong>The denominator is the horizon, not the periods that held something.</strong> A period
 * in which the network holds nothing is a period this configuration ran lean, and it is precisely the
 * observation the metric exists to capture; excluding it would report the mean of the periods in
 * which the answer happened to be interesting. {@link PeriodTrace#endingInventory()} is already the
 * whole-network total at the end of the period — the figure {@code RUN_TIMESERIES.ending_inventory}
 * stores and the per-element {@code onHand} series sums to — so nothing here re-derives it.
 *
 * <p>Computed over the <strong>disrupted</strong> set, like every other descriptive metric in this
 * package. What a configuration held while the scenario ran is the question; the baseline set exists
 * to give the recovery metrics something to measure against, and a mean over it would
 * answer a question nobody asked of this row.
 *
 * <h2>Why this is {@link MetricDirection#NEUTRAL}</h2>
 *
 * <p><strong>Neither direction is better, and saying so is the point.</strong> A leaner network is
 * cheaper to hold — {@code TOTAL_COST} prices exactly that, and without it "the Phase 2 search
 * would set every buffer to its upper bound" ({@code TotalCostCalculator}) — while a more buffered
 * one absorbs a disruption its supply cannot cover. That is the trade-off a resilience study is
 * conducted to characterise, not one this suite is entitled to rank, so the comparison view shows
 * these rows with <em>no winner highlighted</em> and the radar chart excludes them — an axis whose
 * outward direction means nothing is worse than a missing axis. The metrics
 * that do rank the same behaviour are already in the suite: {@code TOTAL_COST} prices holding it and
 * {@code FILL_RATE} measures what running out costs.
 *
 * <p><strong>Source.</strong> Inventory is the second-largest family in the author's SLR metric
 * catalog — an inventory or WIP measure appears in <strong>19 of the 57 included studies</strong>
 * ({@code metric-catalog.md}) — and it is the family that had no calculator until this one.
 * Three of those studies fix the reading taken here. Ivanov et al. (2017) carry total inventory as an
 * explicit objective (J3) alongside service and cost, which is what makes it a metric to be reported
 * beside them rather than a cost component folded into one of them. Sindhwani et al. (2023) report an
 * average daily inventory KPI — a mean over the horizon of a period-end level, which is this
 * arithmetic exactly. Kristianto et al. (2014) treat inventory positioning as a decision lever whose
 * effect has to be observable, which is the Phase 2 case for the row existing at all: a
 * lever nothing measures cannot be searched over.
 *
 * <p>Its in-transit counterpart is {@link AvgPipelineCalculator}, and the pair is meant to be read
 * together: the same material is on a shelf in one and on a truck in the other, and a configuration
 * that lowers one by raising the other has moved its buffer rather than removed it.
 * {@code samples/four-echelon-playback/README.md} §6.1 works both by hand.
 */
@Component
@Order(190)
public class AvgInventoryCalculator implements MetricCalculator {

    public static final String CODE = "AVG_INVENTORY";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /** {@link MetricDirection#NEUTRAL} — see the class note; leaner is not better, nor worse. */
    @Override
    public MetricDirection direction() {
        return MetricDirection.NEUTRAL;
    }

    /**
     * Quantities, not durations: this is a count of units held, and no period unit applies to it.
     */
    @Override
    public boolean timeValued() {
        return false;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        List<ReplicationTrace> replications = ctx.requireTraces().disrupted();
        double[] observations = new double[replications.size()];
        for (int i = 0; i < replications.size(); i++) {
            observations[i] = meanEndingInventory(replications.get(i));
        }
        return ReplicationStatistics.summarise(CODE, observations);
    }

    /**
     * One replication's mean end-of-period stock.
     *
     * <p>A replication with no periods at all returns 0 rather than {@code NaN}: an empty horizon is
     * a network that was never run, and {@link ReplicationStatistics#summarise} would carry a
     * {@code NaN} into the mean and the interval alike. The case is unreachable through
     * {@code SimulationService}, which validates the horizon before the 202.
     */
    private static double meanEndingInventory(ReplicationTrace replication) {
        List<PeriodTrace> periods = replication.periods();
        if (periods.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (PeriodTrace period : periods) {
            total += period.endingInventory();
        }
        return total / periods.size();
    }
}
