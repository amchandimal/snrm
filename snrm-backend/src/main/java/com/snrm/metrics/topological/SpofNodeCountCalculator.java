package com.snrm.metrics.topological;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code SPOF_NODE_COUNT} — the node half of the "number of nodes/links whose single removal
 * disconnects any customer from all supply".
 *
 * <p>How many <em>facilities</em> — suppliers, plants, distribution centres — carry the whole of
 * some customer's supply on their own. {@link SpofAnalysis} defines the test and does the work; this
 * class exists to give that half of the answer a code of its own.
 *
 * <h2>Why it is reported apart from the arcs</h2>
 *
 * <p>Because the two suggest different remedies, and the combined figure hides which one applies. A
 * network with six single points of failure is one problem if all six are nodes — the fix is a
 * second site, or a second source qualified for the same product — and a quite different one if all
 * six are arcs, where the fix is a lane, a carrier or a routing agreement and the facilities are
 * already there. Reading {@code SPOF_COUNT = 6} tells a researcher the configuration is fragile;
 * reading {@code 1} node and {@code 5} arcs tells them where to spend.
 *
 * <p>{@code SPOF_COUNT} is kept as the aggregate rather than left to be added up by the reader: the
 * comparison matrix ranks whole rows, and "which configuration has fewer single points of failure"
 * is a question about the total.
 *
 * <p>Reported at {@link com.snrm.metrics.MetricScope#NETWORK} scope as a count.
 */
@Component
@Order(20)
public class SpofNodeCountCalculator implements MetricCalculator {

    public static final String CODE = "SPOF_NODE_COUNT";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.TOPOLOGICAL;
    }

    /** {@link MetricDirection#LOWER_IS_BETTER} — fewer indispensable facilities is better. */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        return List.of(MetricValue.network(CODE, SpofAnalysis.of(ctx).nodes()));
    }
}
