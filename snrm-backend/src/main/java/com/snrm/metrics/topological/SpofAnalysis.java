package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;

/**
 * The single-point-of-failure census, computed once and read by all three SPOF calculators.
 *
 * <p>The census counts one thing — the nodes and links whose single removal disconnects any
 * customer from all supply — and the suite reports it three ways: the node count, the arc count and
 * their total. The <em>test</em> behind all three is identical, and it is not cheap: one traversal
 * per node plus one per arc, each of them O(V + E). Running it three times would triple the most
 * expensive structural metric after {@code NODE_CRITICALITY} to produce numbers that are already in
 * hand, so the census is done here and memoised through {@link MetricContext#derived}, exactly as
 * {@link GraphIndex} and {@code ServiceableDemand} are.
 *
 * <p>The alternative — one calculator emitting three {@code MetricValue}s — was rejected because a
 * calculator's {@link com.snrm.metrics.MetricCalculator#direction() direction} and its position in
 * the suite are declared per <em>code</em>, not per value: three codes on one bean would leave the
 * comparison matrix unable to rank two of them and the registry unable to order them.
 * Three thin beans over one shared derivation keeps "adding a metric is one class" true without
 * paying for the work twice.
 *
 * <h2>The test, precisely</h2>
 *
 * <p>A customer is <em>supplied</em> if a directed path reaches it from a supply origin — the fixed
 * origin set defined in this package's Javadoc. An element is a single point of failure if removing
 * it, and nothing else, leaves some customer unsupplied that was supplied before.
 *
 * <p>Three details decide what the numbers mean:
 *
 * <ul>
 *   <li><strong>Only customers supplied in the intact network count.</strong> A customer with no
 *       inbound path is already cut off, and an element cannot be blamed for a disconnection that
 *       predates it — without this the metric would count almost every element of a half-built
 *       network.
 *       ({@code NetworkChecks} refuses such a network at import for the same reason it matters
 *       here.)</li>
 *   <li><strong>Removing a customer does not disconnect it.</strong> The test asks about the
 *       customers that remain. A node whose only consequence is its own disappearance is not a point
 *       of failure, it is the failure.</li>
 *   <li><strong>Connectivity, not capacity.</strong> This asks whether any path survives, not
 *       whether enough capacity does. The capacity question is {@code NODE_CRITICALITY}'s, and the
 *       two answer differently on purpose: an element can be uncritical here — an alternative route
 *       exists — while its loss still halves serviceable demand because that route is narrow.</li>
 * </ul>
 *
 * <p>Which elements they are is not persisted: the counts are the metrics, and an element-by-element
 * answer would be a different one ("is this a SPOF") better served by asking the graph directly.
 */
final class SpofAnalysis {

    private static final Object KEY = new Object();

    private final int nodes;
    private final int arcs;

    /** The census for this snapshot, computed on the first call and remembered for the rest. */
    static SpofAnalysis of(MetricContext ctx) {
        return ctx.derived(KEY, () -> census(GraphIndex.of(ctx)));
    }

    private SpofAnalysis(int nodes, int arcs) {
        this.nodes = nodes;
        this.arcs = arcs;
    }

    /** Nodes whose single removal strands a customer — {@code SPOF_NODE_COUNT}. */
    int nodes() {
        return nodes;
    }

    /** Arcs whose single removal strands a customer — {@code SPOF_ARC_COUNT}. */
    int arcs() {
        return arcs;
    }

    /**
     * Both together — {@code SPOF_COUNT}, the headline figure.
     *
     * <p>A plain sum and not a separate search: an element is a node or an arc and never both, so
     * the two sets are disjoint by construction and nothing can be double-counted.
     */
    int total() {
        return nodes + arcs;
    }

    private static SpofAnalysis census(GraphIndex index) {
        int n = index.size();
        if (n == 0) {
            return new SpofAnalysis(0, 0);
        }

        boolean[] suppliedIntact = supplied(index, -1, -1);
        int nodes = 0;
        int arcs = 0;

        for (int node = 0; node < n; node++) {
            if (disconnectsACustomer(index, suppliedIntact, node, -1)) {
                nodes++;
            }
        }
        for (int link = 0; link < index.linkCount(); link++) {
            if (index.linkSource(link) >= 0 && index.linkTarget(link) >= 0
                    && disconnectsACustomer(index, suppliedIntact, -1, link)) {
                arcs++;
            }
        }
        return new SpofAnalysis(nodes, arcs);
    }

    /** True if removing this element strands a customer that the intact network reached. */
    private static boolean disconnectsACustomer(GraphIndex index, boolean[] suppliedIntact,
            int excludedNode, int excludedLink) {
        boolean[] supplied = supplied(index, excludedNode, excludedLink);
        for (int customer : index.customers()) {
            if (customer != excludedNode && suppliedIntact[customer] && !supplied[customer]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which nodes a directed path reaches from the supply origins, with one element left out.
     *
     * <p>A single breadth-first search from all origins at once rather than one per origin: reaching
     * a node from <em>any</em> origin is the question, so the searches would only re-tread each
     * other's ground.
     */
    private static boolean[] supplied(GraphIndex index, int excludedNode, int excludedLink) {
        int n = index.size();
        boolean[] reached = new boolean[n];
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int origin : index.origins()) {
            if (origin != excludedNode && !reached[origin]) {
                reached[origin] = true;
                queue[tail++] = origin;
            }
        }
        while (head < tail) {
            int current = queue[head++];
            int[] successors = index.successors(current);
            int[] links = index.outLinks(current);
            for (int i = 0; i < successors.length; i++) {
                int next = successors[i];
                if (links[i] == excludedLink || next == excludedNode || reached[next]) {
                    continue;
                }
                reached[next] = true;
                queue[tail++] = next;
            }
        }
        return reached;
    }
}
