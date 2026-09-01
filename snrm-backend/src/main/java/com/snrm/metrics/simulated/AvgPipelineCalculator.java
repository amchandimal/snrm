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
 * {@code AVG_PIPELINE} — the inventory/absorption family (FR-19).
 *
 * <blockquote>"{@code AVG_INVENTORY}, {@code AVG_PIPELINE} — Mean end-of-period total on-hand stock,
 * and mean in-transit (WIP), across the horizon — per replication, then mean and 95% CI. Both
 * {@code NEUTRAL}: leaner versus more buffered is the trade-off under study, not a ranking."
 * </blockquote>
 *
 * <pre>
 *   AVG_PIPELINE(replication) = ( Σ_t inPipeline(t) ) / H
 * </pre>
 *
 * <p>{@link PeriodTrace#inPipeline()} is material "shipped and paid for but not yet anywhere"
 * — in transit on an arc with a lead time, or sitting out a node's processing dwell. It is
 * the sibling of {@link AvgInventoryCalculator}'s figure and is deliberately <em>not</em> folded into
 * it: stock on a shelf can be dispatched this period and stock on a truck cannot, so a configuration
 * that has merely moved its buffer from one to the other looks unchanged in a combined number and is
 * a different network to run.
 *
 * <p><strong>Zero is a measurement, not an absence.</strong> A network whose lead times are all zero
 * and whose nodes have no processing dwell holds nothing in flight, so this reports 0.0 — as it does
 * on {@code docs/simulation-verification.md}'s three-node chain (§6.6), where the zero is the stated
 * consequence of a design choice rather than a gap. That is the opposite case from the four
 * disruption-relative metrics, which produce <em>no row at all</em> on a run with nothing to recover
 * from, and a reader must not render one as the other.
 *
 * <p>Computed over the <strong>disrupted</strong> set, for the reason {@link AvgInventoryCalculator}
 * states.
 *
 * <h2>Why this is {@link MetricDirection#NEUTRAL}</h2>
 *
 * <p>The same trade-off, one echelon along. A long pipeline is capital in motion and exposure to
 * whatever happens to the arc it is on; a short one is a network with no slack between its rungs and
 * nothing to absorb a delay with. Which is better is the finding, so the comparison view highlights
 * no cell in this row and the radar leaves it out. Where the pipeline is
 * genuinely a cost it is already priced — every unit in flight was produced and moved, so it appears
 * in {@code TOTAL_COST}'s variable and transport terms.
 *
 * <p><strong>Source.</strong> The same inventory family the SLR catalog counts in
 * <strong>19 of 57 studies</strong> ({@code metric-catalog.md}), read as work in process rather than
 * as on-hand stock. Ivanov et al. (2017)'s total-inventory objective (J3) spans both, since a
 * multi-echelon model that omitted in-transit material would under-report exactly the stock a lead
 * time creates; Sindhwani et al. (2023)'s average-daily-inventory KPI is the same mean-over-horizon
 * form; and Kristianto et al. (2014) make inventory positioning a lever, which in a network with lead
 * times is a decision about how much of the buffer sits on arcs rather than at nodes.
 *
 * <p>{@code samples/four-echelon-playback/README.md} §6.1 is the worked example, and the sample
 * exists for this metric: every leg there is one period long, so the pipeline is 29.0 against an
 * on-hand mean of 22.0, and the pair is checkable by hand.
 */
@Component
@Order(200)
public class AvgPipelineCalculator implements MetricCalculator {

    public static final String CODE = "AVG_PIPELINE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /** {@link MetricDirection#NEUTRAL} — see the class note; more in flight is not better, nor worse. */
    @Override
    public MetricDirection direction() {
        return MetricDirection.NEUTRAL;
    }

    /** A quantity of units in flight, not a count of periods. */
    @Override
    public boolean timeValued() {
        return false;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        List<ReplicationTrace> replications = ctx.requireTraces().disrupted();
        double[] observations = new double[replications.size()];
        for (int i = 0; i < replications.size(); i++) {
            observations[i] = meanInPipeline(replications.get(i));
        }
        return ReplicationStatistics.summarise(CODE, observations);
    }

    /** One replication's mean in-transit quantity. Empty horizon → 0, per {@link AvgInventoryCalculator}. */
    private static double meanInPipeline(ReplicationTrace replication) {
        List<PeriodTrace> periods = replication.periods();
        if (periods.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (PeriodTrace period : periods) {
            total += period.inPipeline();
        }
        return total / periods.size();
    }
}
