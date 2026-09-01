package com.snrm.metrics.simulated;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import com.snrm.simulation.ReplicationTrace;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code FILL_RATE} — the service family.
 *
 * <blockquote>"{@code FILL_RATE}, {@code SERVICE_LEVEL} — Served / total demand per period and
 * across the horizon."</blockquote>
 *
 * <pre>
 *   FILL_RATE(replication) = Σ_t served(t) / Σ_t demand(t)
 * </pre>
 *
 * <p><strong>A ratio of sums, not a mean of ratios.</strong> The two are different numbers and the
 * difference is not small: the ratio of sums weights each period by the demand that arose in it,
 * while a mean of per-period fill rates weights every period equally and lets a quiet period with
 * two units of demand count as much as a peak with two hundred. Served / total demand across the
 * horizon is the first of the two. The second is available where it is the right question
 * — {@code MIN_FILL_RATE} works on the per-period series — so both readings exist in the suite and
 * neither is hidden inside the other.
 *
 * <p>This is <strong>type-2 service</strong> in the inventory-theory sense: the fraction of demanded
 * units that were delivered. Its type-1 counterpart, the fraction of periods in which demand was met
 * in full, is {@link ServiceLevelCalculator} — see that class for why the suite carries both.
 *
 * <p>Computed over the <strong>disrupted</strong> replications. The baseline set exists to give the
 * recovery metrics something to measure against; the service metrics describe what the
 * network actually did.
 *
 * <p><strong>Source.</strong> Fill rate as served-over-demanded is the standard service measure in
 * the service/cost trade-off literature the RQ5 synthesis draws this family from, in particular
 * Badejo &amp; Ierapetritou (2022).
 */
@Component
@Order(100)
public class FillRateCalculator implements MetricCalculator {

    public static final String CODE = "FILL_RATE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#HIGHER_IS_BETTER} — more demand met is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.HIGHER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        List<ReplicationTrace> replications = ctx.requireTraces().disrupted();
        double[] observations = new double[replications.size()];
        for (int i = 0; i < replications.size(); i++) {
            observations[i] = replications.get(i).fillRate();
        }
        return ReplicationStatistics.summariseBounded(CODE, observations, 0, 1);
    }
}
