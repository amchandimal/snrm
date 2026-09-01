package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;
import com.snrm.network.GraphLink;
import com.snrm.network.GraphNode;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maximum serviceable demand, and the per-node criticality derived from it.
 *
 * <p>This is the arithmetic behind {@code NODE_CRITICALITY} — "relative drop in max serviceable
 * demand when the node is removed" — and, through the ranking it produces, behind the removal order
 * of {@code ROBUSTNESS_TARGETED}. Both read it through {@link #criticality(MetricContext)}, which
 * memoises on the context: it costs one maximum flow per node and computing it twice would double
 * the most expensive thing in the suite ({@code MetricContext}).
 *
 * <h2>The flow network</h2>
 *
 * <p>"Maximum serviceable demand" is the largest quantity that can reach customers in one period
 * given every capacity in the network at once. That is a maximum-flow problem, and it is solved as
 * one on a graph built from the snapshot (JGraphT):
 *
 * <pre>
 *   SOURCE ──∞──▶ in(origin)                       one arc per supply origin
 *   in(v) ──node capacity──▶ out(v)                every node, split so its own throughput binds
 *   out(u) ──arc capacity──▶ in(w)                 one arc per link u→w
 *   out(c) ──demand(c)──▶ SINK                     one arc per customer
 * </pre>
 *
 * <p><strong>Splitting each node into an in- and an out-half</strong> is what makes a node's
 * capacity a constraint at all: an arc capacity limits one link, whereas a plant that can process
 * 400 units a period limits everything passing through it however many links arrive. Without the
 * split there is nowhere to put that number.
 *
 * <p><strong>"Unconstrained" is the network's total demand, not infinity.</strong> A null capacity
 * means no ceiling, and flow into the sink can never exceed the sum of the sink arcs, so total
 * demand is a bound that provably cannot bind — it is the smallest such number, and using a finite
 * one keeps the arithmetic exact where {@code Double.POSITIVE_INFINITY} would put an
 * implementation-defined value into a subtraction. The bound is computed once on the intact network
 * and is not reduced when a node is removed, so removing a customer lowers the answer by that
 * customer's demand and nothing else.
 *
 * <h2>Criticality</h2>
 *
 * <pre>
 *   criticality(v) = ( D(intact) − D(without v) ) / D(intact)
 * </pre>
 *
 * <p>1 means the network serves nothing without that node; 0 means it is redundant. Two readings are
 * worth stating because they surprise people, and both are the definition working correctly:
 *
 * <ul>
 *   <li><strong>A customer's criticality is its share of demand.</strong> Removing a customer
 *       removes the demand it was there to represent, so the drop is exactly that demand. That is
 *       what "relative drop in serviceable demand" says, and it is informative — it ranks customers
 *       by exposure — but it is not the same quantity as a supplier's criticality, and a table
 *       putting the two side by side should say so.</li>
 *   <li><strong>Criticality is not centrality.</strong> A sole supplier is a degree-one leaf and is
 *       structurally peripheral, yet its criticality is 1. That divergence is why the suite
 *       carries both {@code NODE_CRITICALITY} and the robustness indices rather than one of
 *       them.</li>
 * </ul>
 */
final class ServiceableDemand {

    private static final Object KEY = new Object();

    /** Vertex numbers 0 and 1 in the flow network; node halves start at 2. */
    private static final int SOURCE = 0;
    private static final int SINK = 1;

    private ServiceableDemand() {
    }

    /**
     * Per-node criticality, keyed by node id, memoised for the life of the context.
     *
     * <p>Every node gets an entry, including ones whose criticality is zero: a table with a row per
     * node is what the per-node criticality view and the node-size encoding both
     * read, and a missing row would have to be interpreted rather than displayed.
     *
     * @return an insertion-ordered map in snapshot node order; empty for an empty network
     */
    static Map<Long, Double> criticality(MetricContext ctx) {
        return ctx.derived(KEY, () -> compute(GraphIndex.of(ctx)));
    }

    private static Map<Long, Double> compute(GraphIndex index) {
        Map<Long, Double> criticality = new LinkedHashMap<>();
        double intact = maxServiceableDemand(index, -1);
        for (int i = 0; i < index.size(); i++) {
            if (intact <= 0) {
                // Nothing can be served at all — no origin, no demand, or no path between them.
                // Every node is then equally irrelevant to a quantity that is zero either way, and
                // 0/0 has no reading that is not invented.
                criticality.put(index.nodeId(i), 0.0);
                continue;
            }
            double without = maxServiceableDemand(index, i);
            criticality.put(index.nodeId(i), snap((intact - without) / intact));
        }
        return criticality;
    }

    /**
     * The largest demand the network can serve in one period.
     *
     * @param excluded index of a node to leave out, with its incident arcs, or -1 for the intact
     *                 network
     */
    static double maxServiceableDemand(GraphIndex index, int excluded) {
        double unconstrained = index.totalDemand();
        if (unconstrained <= 0 || index.origins().length == 0) {
            // No demand to serve, or nowhere for material to enter. Either way the answer is zero
            // and building the flow network would only be a slower way to say so.
            return 0;
        }

        SimpleDirectedWeightedGraph<Integer, DefaultWeightedEdge> flow =
                new SimpleDirectedWeightedGraph<>(DefaultWeightedEdge.class);
        flow.addVertex(SOURCE);
        flow.addVertex(SINK);
        for (int i = 0; i < index.size(); i++) {
            if (i == excluded) {
                continue;
            }
            flow.addVertex(in(i));
            flow.addVertex(out(i));
            GraphNode node = index.node(i);
            Double capacity = node.capacityPerPeriod();
            addArc(flow, in(i), out(i), capacity == null ? unconstrained : capacity);
        }
        for (int origin : index.origins()) {
            if (origin != excluded) {
                addArc(flow, SOURCE, in(origin), unconstrained);
            }
        }
        for (int customer : index.customers()) {
            if (customer != excluded && index.demand(customer) > 0) {
                addArc(flow, out(customer), SINK, index.demand(customer));
            }
        }
        // Arc capacities come from the snapshot's JGraphT edge weights rather than from the record
        // field. They are the same number by construction (NetworkGraph.jgrapht()), and reading the
        // weight is what makes "directed weighted graph" mean something: the weight of an arc in
        // this model *is* what it can carry, and this is the computation that consumes it.
        Graph<Long, GraphLink> weighted = index.graph().jgrapht();
        for (int e = 0; e < index.linkCount(); e++) {
            int source = index.linkSource(e);
            int target = index.linkTarget(e);
            if (source < 0 || target < 0 || source == excluded || target == excluded) {
                continue;
            }
            double weight = weighted.getEdgeWeight(index.graph().links().get(e));
            addArc(flow, out(source), in(target), Double.isInfinite(weight) ? unconstrained : weight);
        }

        return new PushRelabelMFImpl<>(flow).getMaximumFlowValue(SOURCE, SINK);
    }

    /**
     * Adds an arc, or widens the one already there.
     *
     * <p>A simple graph cannot hold two arcs between the same pair, and {@code uq_link} means the
     * snapshot cannot produce them — but two parallel arcs and one arc of their combined capacity
     * carry exactly the same flow, so summing is both the correct fallback and the one that cannot
     * silently drop capacity the way a rejected {@code addEdge} would.
     */
    private static void addArc(SimpleDirectedWeightedGraph<Integer, DefaultWeightedEdge> flow,
            int from, int to, double capacity) {
        DefaultWeightedEdge existing = flow.getEdge(from, to);
        if (existing != null) {
            flow.setEdgeWeight(existing, flow.getEdgeWeight(existing) + Math.max(0, capacity));
            return;
        }
        DefaultWeightedEdge arc = flow.addEdge(from, to);
        if (arc != null) {
            flow.setEdgeWeight(arc, Math.max(0, capacity));
        }
    }

    /**
     * Clamps a criticality into {@code [0,1]} and pulls a near-endpoint onto the endpoint.
     *
     * <p>The two endpoints are the values that carry a claim: 0 means "this node is redundant" and 1
     * means "without it nothing is served", and both are statements a reader will quote. Maximum
     * flow is floating-point arithmetic, so an exactly-redundant node can come back as 1.4e-16 —
     * true to the last bit and false as a sentence, since it would render as a non-zero criticality
     * in a results table and rank above a node that is equally redundant. The tolerance is twelve
     * orders of magnitude below any difference a supply network can express.
     */
    private static double snap(double drop) {
        double clamped = Math.min(1, Math.max(0, drop));
        if (clamped <= ENDPOINT_TOLERANCE) {
            return 0;
        }
        return clamped >= 1 - ENDPOINT_TOLERANCE ? 1 : clamped;
    }

    private static final double ENDPOINT_TOLERANCE = 1e-12;

    private static int in(int nodeIndex) {
        return 2 + 2 * nodeIndex;
    }

    private static int out(int nodeIndex) {
        return 3 + 2 * nodeIndex;
    }
}
