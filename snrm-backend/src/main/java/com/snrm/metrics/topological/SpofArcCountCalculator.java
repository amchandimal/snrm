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
 * {@code SPOF_ARC_COUNT} — the arc half of the "number of nodes/links whose single removal
 * disconnects any customer from all supply".
 *
 * <p>How many <em>lanes</em> carry the whole of some customer's supply on their own.
 * {@link SpofAnalysis} defines the test and does the work; this class exists to give that half of
 * the answer a code of its own, and {@link SpofNodeCountCalculator} says why the halves are worth
 * separating.
 *
 * <p>An arc is counted only where both of its endpoints are in the snapshot. An arc with a dangling
 * endpoint cannot be traversed, so it cannot be the thing a customer's supply depends on — and the
 * foreign keys mean it should not occur at all; {@link GraphIndex} tolerates it rather
 * than making every traversal a failure path.
 *
 * <p>Reported at {@link com.snrm.metrics.MetricScope#NETWORK} scope as a count.
 */
@Component
@Order(30)
public class SpofArcCountCalculator implements MetricCalculator {

    public static final String CODE = "SPOF_ARC_COUNT";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.TOPOLOGICAL;
    }

    /** {@link MetricDirection#LOWER_IS_BETTER} — fewer indispensable lanes is better. */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        return List.of(MetricValue.network(CODE, SpofAnalysis.of(ctx).arcs()));
    }
}
