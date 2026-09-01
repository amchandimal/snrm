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

import java.util.Arrays;
import java.util.List;

/**
 * {@code MIN_FILL_RATE} — the absorption family.
 *
 * <blockquote>"{@code MIN_FILL_RATE}, {@code LOSS_AREA} — Worst per-period fill rate; area between
 * baseline and disrupted performance curves (resilience triangle)."</blockquote>
 *
 * <pre>
 *   MIN_FILL_RATE(replication) = min over periods with demand of  served(t) / demand(t)
 * </pre>
 *
 * <p>The depth of the resilience triangle, where {@link LossAreaCalculator} is its area. It is the
 * suite's only measure of <em>how bad it got</em> rather than <em>how much was lost in total</em>,
 * and the two come apart in the case a resilience study most needs to distinguish: a shallow
 * disruption lasting twenty periods and a total outage lasting one can have identical loss areas and
 * completely different minima. A network whose worst period still served 80% of demand absorbed the
 * shock; one that fell to zero did not, however quickly it recovered.
 *
 * <p><strong>Periods with no demand are skipped</strong>, not counted as a fill rate of 1 or of 0.
 * Serving nothing when nothing was asked is neither success nor failure, and either reading would
 * corrupt a minimum: counting them as 1 is harmless but pointless, counting them as 0 would report
 * total collapse for every network with a quiet period in its horizon.
 *
 * <p>Aggregated across replications as a <strong>mean of the per-replication minima</strong>, which
 * is the expected worst case rather than the worst case observed. The worst observed period across
 * the whole run is a different statistic — it is the minimum of a hundred minima, and it converges
 * downward as replications are added rather than to anything — so it is not what a confidence
 * interval can be put on. The tail of the distribution is what {@code CVAR_COST} is for.
 *
 * <p><strong>Source.</strong> Minimum performance is the "robustness/absorption" capture mechanism
 * of the RQ5 synthesis; the resilience-triangle depth is standard in the disruption-recovery
 * literature.
 */
@Component
@Order(120)
public class MinFillRateCalculator implements MetricCalculator {

    public static final String CODE = "MIN_FILL_RATE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#HIGHER_IS_BETTER} — a higher worst period is a better configuration.
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
            double worst = Double.POSITIVE_INFINITY;
            for (PeriodTrace period : replication.periods()) {
                if (period.hasDemand()) {
                    worst = Math.min(worst, period.fillRate());
                }
            }
            if (Double.isFinite(worst)) {
                observations[produced++] = worst;
            }
        }
        if (produced == 0) {
            return List.of();
        }
        return ReplicationStatistics.summariseBounded(CODE, Arrays.copyOf(observations, produced),
                0, 1);
    }
}
