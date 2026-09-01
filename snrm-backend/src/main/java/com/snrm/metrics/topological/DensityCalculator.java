package com.snrm.metrics.topological;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code DENSITY} — a standard graph statistic on the directed network.
 *
 * <p>The fraction of the arcs that could exist which do:
 *
 * <pre>
 *   DENSITY = m / ( n · (n − 1) )
 * </pre>
 *
 * <p>with <em>n</em> nodes and <em>m</em> links. The denominator is the number of ordered pairs,
 * because the network is directed and {@code A→B} is a different arrangement from {@code B→A}: an
 * undirected denominator would report twice the density and make a six-node network look twice as
 * interconnected as it is. Self-loops are excluded from the count of possible arcs, which
 * {@code ck_link_no_self_loop} guarantees is also true of the numerator.
 *
 * <p>The result is in {@code [0, 1]}. Real supply networks sit near the bottom of it — an echelon
 * structure allows only a small fraction of all ordered pairs — so the metric is read as a relative
 * quantity between configurations, not against an absolute scale. That is precisely how the
 * comparison view presents it: adding a backup supplier link raises it, and the size of
 * the rise is the information.
 *
 * <p>A network of fewer than two nodes has no pair that could be connected. The density of such a
 * network is reported as 0 rather than as undefined, since the alternative is a hole in a suite the
 * editor recomputes on every edit — including the edit that creates the first node.
 */
@Component
@Order(10)
public class DensityCalculator implements MetricCalculator {

    public static final String CODE = "DENSITY";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.TOPOLOGICAL;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        int n = ctx.graph().nodes().size();
        int m = ctx.graph().links().size();
        double possible = (double) n * (n - 1);
        return List.of(MetricValue.network(CODE, possible <= 0 ? 0 : m / possible));
    }
}
