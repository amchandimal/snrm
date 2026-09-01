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
 * {@code CVAR_COST} (α = 0.95) — the tail of the economic family.
 *
 * <blockquote>"Expected total cost in the worst 5% of replications."</blockquote>
 *
 * <pre>
 *   k = max( 1 , ⌈ N · (1 − α) ⌉ )
 *   CVAR_COST = mean of the k largest replication total costs
 * </pre>
 *
 * <p>Conditional value at risk: not the cost that is exceeded 5% of the time (that is value at risk)
 * but the <em>average</em> cost when it is. The distinction is the whole reason CVaR is in the suite
 * rather than a quantile — VaR says nothing about how bad the bad case is, so two networks with
 * identical VaR can have completely different exposure, and a resilience study is about exactly the
 * cases in the tail.
 *
 * <p>At the default N = 100 this is the mean of the five most expensive replications.
 *
 * <h2>Two things to know when reading it</h2>
 *
 * <p><strong>It carries no confidence interval, and that is not an omission.</strong> Every other
 * metric here is a mean of one observation per replication, so its interval follows from the sample.
 * CVaR is a functional of the <em>whole set</em> — there is one CVaR per run, not one per
 * replication — and the standard error of an empirical tail mean is not the standard error of a
 * mean. Publishing a plausible-looking interval computed the same way as the others would overstate
 * the precision of the number in the suite that has the least of it. {@code ciLow} and {@code ciHigh}
 * are therefore null, an interval being published only where it is meaningful.
 *
 * <p><strong>It rests on {@code k} observations, not on N.</strong> At N = 100 the estimate comes
 * from five replications and is correspondingly noisy; doubling N halves neither the noise nor the
 * runtime usefully. A run whose CVaR matters should say what N was, and the honest way to tighten it
 * is more replications, which is the mitigation for this family.
 *
 * <p><strong>Source.</strong> CVaR-based robustness is the RQ5 tail-risk capture mechanism,
 * following Liu et al. (2021) and Alikhani et al. (2023).
 */
@Component
@Order(170)
public class CvarCostCalculator implements MetricCalculator {

    public static final String CODE = "CVAR_COST";

    /** The confidence level is stated on the metric itself: {@code CVAR_COST (α=0.95)}. */
    public static final double ALPHA = 0.95;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#LOWER_IS_BETTER} — a cheaper worst-case tail is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        List<ReplicationTrace> replications = ctx.requireTraces().disrupted();
        if (replications.isEmpty()) {
            return List.of();
        }
        double[] costs = new double[replications.size()];
        for (int i = 0; i < replications.size(); i++) {
            costs[i] = replications.get(i).totalCost();
        }
        // No interval: see the class note.
        return List.of(MetricValue.network(CODE,
                ReplicationStatistics.conditionalValueAtRisk(costs, ALPHA)));
    }
}
