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
 * {@code SERVICE_LEVEL} — the service family, beside {@link FillRateCalculator}.
 *
 * <pre>
 *   SERVICE_LEVEL(replication) = periods in which demand was met in full
 *                                ────────────────────────────────────────
 *                                        periods that had any demand
 * </pre>
 *
 * <h2>Why this is not a second fill rate</h2>
 *
 * <p>One definition covers two codes — "served / total demand per period and across the
 * horizon" — and reading it as one quantity computed twice would put the same number in the results
 * table under two names, which is a defect however faithfully it transcribes the sentence. The
 * sentence names two things, and inventory theory names them too:
 *
 * <ul>
 *   <li><strong>Type 2 (fill rate).</strong> What fraction of demanded <em>units</em> were
 *       delivered. {@link FillRateCalculator}.</li>
 *   <li><strong>Type 1 (cycle service level).</strong> What fraction of <em>periods</em> were
 *       served in full — this class.</li>
 * </ul>
 *
 * <p>They answer different questions and can diverge sharply, which is exactly why a resilience
 * suite should carry both. A network that misses 2% of every period's demand has a fill rate of 0.98
 * and a service level of <strong>0</strong>: no period was ever satisfied. One that serves nine
 * periods perfectly and fails the tenth completely has a fill rate of 0.9 and a service level of 0.9.
 * The first is a chronically undersized network; the second is a network with a disruption in it.
 * A suite reporting one number could not tell a reader which they were looking at, and the whole
 * argument for six metric families is that no single lens should be trusted to.
 *
 * <p><strong>Periods with no demand are excluded</strong> rather than counted as successes. A period
 * nobody asked anything of is not evidence that the network can serve, and counting it as one would
 * let a horizon padded with quiet periods report a service level approaching 1 for a network that
 * fails whenever it is actually used. If no period has any demand the metric is undefined and
 * produces no value, which a calculator is allowed to do.
 *
 * <p><strong>Source.</strong> The type-1/type-2 distinction is standard inventory-theoretic service
 * measurement; the RQ5 synthesis records both under the service family.
 */
@Component
@Order(110)
public class ServiceLevelCalculator implements MetricCalculator {

    public static final String CODE = "SERVICE_LEVEL";

    /**
     * How close to full counts as full.
     *
     * <p>The allocation is solved on the fixed-point grid of {@code Quantiser}, so a period that is
     * served completely can come back as 49.9995 against a demand of 50. Without a tolerance the
     * metric would report a service level of 0 for a network that failed nothing, and the failure
     * would look like a disruption rather than like arithmetic. The tolerance is relative, so it
     * scales with the demand it is applied to, and is far tighter than the grid's own residual.
     */
    private static final double RELATIVE_TOLERANCE = 1e-9;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#HIGHER_IS_BETTER} — more periods served in full is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.HIGHER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        List<ReplicationTrace> replications = ctx.requireTraces().disrupted();
        double[] observations = new double[replications.size()];
        int produced = 0;
        for (ReplicationTrace replication : replications) {
            int withDemand = 0;
            int fullyServed = 0;
            for (PeriodTrace period : replication.periods()) {
                if (!period.hasDemand()) {
                    continue;
                }
                withDemand++;
                if (period.servedDemand()
                        >= period.totalDemand() * (1 - RELATIVE_TOLERANCE)) {
                    fullyServed++;
                }
            }
            if (withDemand > 0) {
                observations[produced++] = (double) fullyServed / withDemand;
            }
        }
        if (produced == 0) {
            // No replication had any demand at all. Undefined rather than zero — see the class note.
            return List.of();
        }
        return ReplicationStatistics.summariseBounded(CODE,
                java.util.Arrays.copyOf(observations, produced), 0, 1);
    }
}
