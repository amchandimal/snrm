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
 * {@code TOTAL_COST} — the economic family.
 *
 * <pre>
 *   TOTAL_COST(replication) = Σ_t ( fixed + variable + transport + holding + shortage )
 * </pre>
 *
 * <p>Five components, each with a reason to be in the total:
 *
 * <table border="1">
 *   <caption>What each component is, and what it makes the metric sensitive to</caption>
 *   <tr><th>Component</th><th>Charged on</th><th>Why it belongs</th></tr>
 *   <tr><td>Fixed</td><td>every node, every period</td>
 *       <td>The cost of <em>having</em> the network. It is what makes the topology levers
 *           — opening and closing nodes — visible in cost at all.</td></tr>
 *   <tr><td>Variable</td><td>throughput × {@code varCost}</td>
 *       <td>Production and handling. Falls when a disruption stops material moving, which is why
 *           the shortage term has to exist.</td></tr>
 *   <tr><td>Transport</td><td>link flow × {@code unitCost}</td>
 *       <td>Where re-routing shows up: a disruption that is absorbed by a longer, dearer path
 *           appears here and nowhere else.</td></tr>
 *   <tr><td>Holding</td><td>ending inventory × holding rate</td>
 *       <td>What redundancy costs. Without it, safety stock would be free and the Phase 2 search
 *           would set every buffer to its upper bound.</td></tr>
 *   <tr><td>Shortage</td><td>unmet demand × the product's unit value</td>
 *       <td><strong>The one that keeps the metric honest.</strong> Without it a network that serves
 *           nothing is the cheapest network there is, {@code DISRUPTION_COST_DELTA} goes negative,
 *           and the cost/resilience Pareto front is a straight line to doing
 *           nothing.</td></tr>
 * </table>
 *
 * <p><strong>The shortage price is not what decided the routing.</strong> The minimum-cost flow
 * uses a dominating penalty so that service is maximised first and cost minimised second —
 * see {@code FlowAllocator} for why a price-based penalty would make {@code FILL_RATE} move when a
 * price changed. The money here is the product's money; it is simply accounted after the fact rather
 * than used to choose the route.
 *
 * <p>Reported in the currency the network's costs were entered in. SNRM stores no currency, so this
 * is a bare number and comparisons across projects are the reader's responsibility.
 *
 * <p><strong>Source.</strong> Total cost against service is the trade-off the RQ5 economic family is
 * built on, following Badejo &amp; Ierapetritou (2022).
 */
@Component
@Order(150)
public class TotalCostCalculator implements MetricCalculator {

    public static final String CODE = "TOTAL_COST";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#LOWER_IS_BETTER} — a cheaper horizon is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        List<ReplicationTrace> replications = ctx.requireTraces().disrupted();
        double[] observations = new double[replications.size()];
        for (int i = 0; i < replications.size(); i++) {
            observations[i] = replications.get(i).totalCost();
        }
        return ReplicationStatistics.summarise(CODE, observations);
    }
}
