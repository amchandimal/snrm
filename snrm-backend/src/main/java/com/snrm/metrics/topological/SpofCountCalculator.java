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
 * {@code SPOF_COUNT} — the number of nodes/links whose single removal disconnects any customer
 * from all supply.
 *
 * <p>One integer: how many single points of failure the configuration has, counting nodes and arcs
 * together. Zero is the property a resilient topology is supposed to have, and every unit above zero
 * is an element that on its own decides whether some customer can be served at all.
 *
 * <p>The test that produces it, and the three readings of it that decide what the number means, are
 * stated once in {@link SpofAnalysis} — this is the total of the two halves reported separately by
 * {@link SpofNodeCountCalculator} and {@link SpofArcCountCalculator}, and by construction it is
 * exactly their sum. It is kept as a metric in its own right rather than left to the reader to add
 * up because the comparison matrix ranks whole rows: "which configuration has fewer single points of
 * failure" is a question about the total, and the total is the figure the definition names.
 *
 * <p>Reported at {@link com.snrm.metrics.MetricScope#NETWORK} scope as a count.
 */
@Component
@Order(40)
public class SpofCountCalculator implements MetricCalculator {

    public static final String CODE = "SPOF_COUNT";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.TOPOLOGICAL;
    }

    /**
     * {@link MetricDirection#LOWER_IS_BETTER} — fewer single points of failure is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        return List.of(MetricValue.network(CODE, SpofAnalysis.of(ctx).total()));
    }
}
