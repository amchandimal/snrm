package com.snrm.metrics.topological;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code ROBUSTNESS_TARGETED} (Rt) — the mean normalised largest-connected-component size
 * over the removal sequence, as nodes are removed by descending criticality.
 *
 * <p>The deliberate-attack half of the Rr/Rt pair (Lou et al. 2020): what happens when the
 * nodes go in the worst plausible order rather than an arbitrary one. The curve and the index over
 * it are exactly those of {@code ROBUSTNESS_RANDOM} — see {@link ComponentCurve} — and the only
 * difference is the order.
 *
 * <h2>The order</h2>
 *
 * <p>Descending {@code NODE_CRITICALITY}, taken from the same computation that metric publishes
 * (through {@link com.snrm.metrics.MetricContext#derived}, so the maximum flows are run once for
 * both). <strong>The ranking is computed once on the intact network and then followed to the end.</strong>
 * It is not re-derived after each removal: ranking by descending criticality is an
 * ordering of the network as it stands, and re-ranking after every step would be a different and
 * far more expensive metric — n² maximum flows rather than n.
 *
 * <p>Ties are broken by node name ascending. Arbitrary, but it has to be <em>something</em>: a
 * research tool cannot let two runs over the same network disagree because a tie fell differently.
 * Name, not node id — an earlier revision used the id, and the id reproduces the
 * database's insertion history rather than the network: the same topology imported fresh, cloned
 * and edited, or restored from a project archive carries different ids in a different
 * order, so its ties ranked differently and its Rt changed while its structure did not. Names are
 * unique per network ({@code uq_node}), survive every export format, and are the order an external
 * verification artifact can actually reproduce.
 *
 * <h2>Reading it beside Rr</h2>
 *
 * <p>Rt is usually below Rr — attacking the important nodes first fragments a network faster than
 * chance does — and the gap measures how much of the network's cohesion rests on a few elements.
 * <strong>It is not guaranteed to be below</strong>, and a network where it is not is telling you
 * something rather than misbehaving: criticality ranks by flow, so a sole supplier feeding one plant
 * ranks first while being a degree-one leaf whose removal costs the largest component a single node.
 * A network whose Rt exceeds its Rr is one whose flow bottlenecks are structurally peripheral —
 * fragile in service terms, cohesive in structural ones. That the two lenses can disagree is why
 * both are reported and why the criticality table is beside them.
 */
@Component
@Order(90)
public class RobustnessTargetedCalculator implements MetricCalculator {

    public static final String CODE = "ROBUSTNESS_TARGETED";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.TOPOLOGICAL;
    }

    /**
     * {@link MetricDirection#HIGHER_IS_BETTER} — holding together longer under targeted loss is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.HIGHER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        GraphIndex index = GraphIndex.of(ctx);
        int n = index.size();
        if (n == 0) {
            return List.of(MetricValue.network(CODE, 0));
        }
        Map<Long, Double> criticality = ServiceableDemand.criticality(ctx);

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator
                .comparingDouble((Integer i) -> -criticality.getOrDefault(index.nodeId(i), 0.0))
                .thenComparing((Integer i) -> index.node(i).name()));

        int[] removalOrder = new int[n];
        for (int i = 0; i < n; i++) {
            removalOrder[i] = order[i];
        }
        return List.of(MetricValue.network(CODE,
                ComponentCurve.robustness(ComponentCurve.curve(index, removalOrder), n)));
    }
}
