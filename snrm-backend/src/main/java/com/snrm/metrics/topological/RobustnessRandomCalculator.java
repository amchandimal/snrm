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
 * {@code ROBUSTNESS_RANDOM} (Rr) — mean normalised largest-connected-component size over
 * the removal sequence, as nodes are removed randomly.
 *
 * <p>The random-failure half of the Rr/Rt pair (Lou et al. 2020): how much of the network
 * holds together as nodes fail without regard to what they are — a fire, a flood, an outage that
 * did not choose its victim. {@link ComponentCurve} defines the curve and the Schneider/Lou index
 * over it — {@code R = Σ S(k) / (n·S(0))}, k = 1 … n — normalised so the value is comparable
 * across networks of different sizes.
 *
 * <p>Read against {@code ROBUSTNESS_TARGETED}: alone the number says little, but the gap between
 * random and targeted removal is a statement about how concentrated a network's fragility is.
 *
 * <h2>"Randomly", made reproducible</h2>
 *
 * <p>A metric that answers differently on each request cannot support a research claim, so
 * what is computed is the <em>expected</em> index over uniformly random removal orders rather than
 * the index of one of them. For a network small enough to enumerate — up to
 * {@link ComponentCurve#EXACT_ENUMERATION_LIMIT} nodes — that expectation is exact and involves no
 * randomness at all: the largest component after k removals depends only on which k nodes are gone,
 * so averaging over all subsets of each size gives precisely the limit an infinite number of trials
 * would converge to. Above that limit it is estimated from a fixed number of seeded orders, which is
 * reproducible but approximate. {@link ComponentCurve} states both, and the step between them.
 */
@Component
@Order(80)
public class RobustnessRandomCalculator implements MetricCalculator {

    public static final String CODE = "ROBUSTNESS_RANDOM";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.TOPOLOGICAL;
    }

    /**
     * {@link MetricDirection#HIGHER_IS_BETTER} — holding together longer under random loss is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.HIGHER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        return List.of(MetricValue.network(CODE,
                ComponentCurve.expectedRobustness(GraphIndex.of(ctx))));
    }
}
