package com.snrm.metrics.topological;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import com.snrm.network.GraphNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code NODE_CRITICALITY} — the relative drop in max serviceable demand when the node is
 * removed, computed for every node.
 *
 * <pre>
 *   criticality(v) = ( D(intact) − D(without v) ) / D(intact)
 * </pre>
 *
 * <p>where <em>D</em> is the largest quantity of customer demand the network can serve in one
 * period under every capacity at once — a maximum flow from the supply origins to the customers on
 * the capacity-annotated snapshot. {@link ServiceableDemand} builds that flow network and documents
 * how node capacities, arc capacities and demands enter it, and why "unconstrained" is the network's
 * total demand rather than infinity.
 *
 * <p>The value is in {@code [0, 1]}: 1 for a node without which nothing can be served, 0 for one
 * whose loss is fully absorbed by alternative routes and spare capacity. It is <em>flow</em>
 * criticality, not centrality — a sole supplier scores 1 while being a degree-one leaf — and reading
 * it beside the robustness indices is the point of having both in the suite (RQ5).
 *
 * <p>One {@link com.snrm.metrics.MetricScope#NODE}-scoped row per node, in snapshot order, which is
 * what the per-node criticality table and the node-size encoding of the editor read.
 *
 * <p><strong>Cost.</strong> One maximum flow per node plus one for the intact network. That is the
 * definition's own cost, not an implementation choice, and it is the term that dominates the
 * topological suite; FR-04's two-second budget at 1,000 nodes rests on the flow network being small
 * — two vertices and one arc per node, one arc per link — and on the answer being shared with
 * {@code ROBUSTNESS_TARGETED} through the context rather than computed twice.
 */
@Component
@Order(70)
public class NodeCriticalityCalculator implements MetricCalculator {

    public static final String CODE = "NODE_CRITICALITY";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.TOPOLOGICAL;
    }

    /**
     * {@link MetricDirection#LOWER_IS_BETTER} — a network whose nodes matter less individually is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        Map<Long, Double> criticality = ServiceableDemand.criticality(ctx);
        List<MetricValue> values = new ArrayList<>(criticality.size());
        for (GraphNode node : ctx.graph().nodes()) {
            values.add(MetricValue.node(CODE, node.id(), node.name(),
                    criticality.getOrDefault(node.id(), 0.0)));
        }
        return values;
    }
}
