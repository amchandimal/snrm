package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;
import com.snrm.network.GraphLink;
import com.snrm.network.GraphNode;
import com.snrm.network.GraphNodeProduct;
import com.snrm.network.NetworkGraph;
import com.snrm.network.NodeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The snapshot re-expressed over dense {@code int} indices, built once per computation.
 *
 * <p>Every calculator in this package walks the graph many times over — {@code NODE_CRITICALITY}
 * once per node, the {@link SpofAnalysis} census once per node <em>and</em> once per link,
 * {@code ROBUSTNESS_RANDOM} once per subset — and a {@code Map<Long, …>} lookup in the inner loop of
 * all of that is the difference between meeting FR-04's two seconds and not. Node ids are
 * {@code bigint} and arbitrarily sparse; positions {@code 0..n-1} index arrays.
 *
 * <p>Shared through {@link MetricContext#derived}, so the arrays are built once for the whole suite
 * rather than once per calculator. It holds no mutable state: the arrays are written during
 * construction and only read afterwards.
 *
 * <p>Deliberately <em>not</em> a replacement for {@link NetworkGraph#jgrapht()}. The JGraphT graph is
 * what the library algorithms run on — the maximum flow behind {@code NODE_CRITICALITY} above all.
 * This is the adjacency the hand-written traversals use, where what is wanted is a hop
 * count or a component and the graph's own weights (capacities) would be the wrong thing to sum.
 */
final class GraphIndex {

    private static final Object KEY = new Object();

    /** Echelons that can originate material — {@code NetworkChecks.SUPPLY_SIDE}. */
    private static final Set<NodeType> SUPPLY_SIDE = Set.of(NodeType.SUPPLIER, NodeType.PLANT);

    private final NetworkGraph graph;
    private final int n;
    private final long[] nodeIds;
    private final GraphNode[] nodes;

    /** Arcs as index pairs, parallel to {@link NetworkGraph#links()}. */
    private final int[] linkSource;
    private final int[] linkTarget;

    private final int[][] out;
    private final int[][] outLinks;
    private final int[][] undirected;

    /** Total per-period demand of each node; zero for anything that is not a customer. */
    private final double[] demand;
    private final double totalDemand;

    /** Supply origins and customers, as index arrays. See the package Javadoc for the definitions. */
    private final int[] origins;
    private final int[] customers;

    static GraphIndex of(MetricContext ctx) {
        return ctx.derived(KEY, () -> new GraphIndex(ctx.graph()));
    }

    private GraphIndex(NetworkGraph graph) {
        this.graph = graph;
        List<GraphNode> nodeList = graph.nodes();
        List<GraphLink> linkList = graph.links();
        this.n = nodeList.size();
        this.nodeIds = new long[n];
        this.nodes = new GraphNode[n];
        this.demand = new double[n];
        // Local, not a field: the sparse bigint ids are needed once, to turn the link endpoints
        // into positions. Everything after this point works in positions.
        Map<Long, Integer> indexById = new HashMap<>(Math.max(16, n * 2));

        for (int i = 0; i < n; i++) {
            GraphNode node = nodeList.get(i);
            nodeIds[i] = node.id();
            nodes[i] = node;
            indexById.put(node.id(), i);
            if (node.type() == NodeType.CUSTOMER) {
                double sum = 0;
                for (GraphNodeProduct product : node.products()) {
                    sum += Math.max(0, product.demandPerPeriod());
                }
                demand[i] = sum;
            }
        }

        int m = linkList.size();
        this.linkSource = new int[m];
        this.linkTarget = new int[m];
        int[] outDegree = new int[n];
        int[] undirectedDegree = new int[n];
        for (int e = 0; e < m; e++) {
            GraphLink link = linkList.get(e);
            Integer source = indexById.get(link.sourceNodeId());
            Integer target = indexById.get(link.targetNodeId());
            // -1 marks an arc whose endpoint is not in the snapshot. Impossible under the foreign
            // keys, and cheaper to tolerate than to make every traversal a failure path.
            linkSource[e] = source == null ? -1 : source;
            linkTarget[e] = target == null ? -1 : target;
            if (linkSource[e] < 0 || linkTarget[e] < 0) {
                continue;
            }
            outDegree[linkSource[e]]++;
            undirectedDegree[linkSource[e]]++;
            undirectedDegree[linkTarget[e]]++;
        }

        this.out = allocate(outDegree);
        this.outLinks = allocate(outDegree);
        int[][] undirectedBuffer = allocate(undirectedDegree);
        int[] outFill = new int[n];
        int[] undirectedFill = new int[n];
        for (int e = 0; e < m; e++) {
            int source = linkSource[e];
            int target = linkTarget[e];
            if (source < 0 || target < 0) {
                continue;
            }
            outLinks[source][outFill[source]] = e;
            out[source][outFill[source]++] = target;
            undirectedBuffer[source][undirectedFill[source]++] = target;
            undirectedBuffer[target][undirectedFill[target]++] = source;
        }
        this.undirected = undirectedBuffer;

        double demandSum = 0;
        for (double value : demand) {
            demandSum += value;
        }
        this.totalDemand = demandSum;

        // Inbound degree decides the origin set, and it is computed here — on the intact graph —
        // precisely because it must not be recomputed after a removal (see the package Javadoc).
        boolean[] hasInbound = new boolean[n];
        for (int e = 0; e < m; e++) {
            if (linkTarget[e] >= 0) {
                hasInbound[linkTarget[e]] = true;
            }
        }
        List<Integer> originList = new ArrayList<>();
        List<Integer> customerList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (SUPPLY_SIDE.contains(nodes[i].type()) && !hasInbound[i]) {
                originList.add(i);
            }
            if (nodes[i].type() == NodeType.CUSTOMER) {
                customerList.add(i);
            }
        }
        this.origins = toArray(originList);
        this.customers = toArray(customerList);
    }

    // ------------------------------------------------------------------------- accessors

    NetworkGraph graph() {
        return graph;
    }

    int size() {
        return n;
    }

    int linkCount() {
        return linkSource.length;
    }

    long nodeId(int index) {
        return nodeIds[index];
    }

    GraphNode node(int index) {
        return nodes[index];
    }

    int linkSource(int link) {
        return linkSource[link];
    }

    int linkTarget(int link) {
        return linkTarget[link];
    }

    /** Successors of a node, by index. */
    int[] successors(int index) {
        return out[index];
    }

    /**
     * The link indices of those successors, positionally parallel to {@link #successors(int)}.
     *
     * <p>Kept beside the successor list rather than derived, because the {@link SpofAnalysis} census
     * behind {@code SPOF_ARC_COUNT} traverses the network once per link and has to be able to skip
     * <em>one</em> arc: without a link-aware adjacency that skip would mean scanning every link at
     * every step, turning a linear traversal into a quadratic one and the metric as a whole into a
     * cubic one.
     */
    int[] outLinks(int index) {
        return outLinks[index];
    }

    /** Neighbours of a node with arc directions dropped — the weak-connectivity adjacency. */
    int[] neighbours(int index) {
        return undirected[index];
    }

    /** This node's total per-period demand; zero for anything that is not a CUSTOMER. */
    double demand(int index) {
        return demand[index];
    }

    /** Demand across the whole network — the ceiling every flow computation is bounded by. */
    double totalDemand() {
        return totalDemand;
    }

    /** Where material enters the model. Fixed for the life of this index. */
    int[] origins() {
        return origins;
    }

    int[] customers() {
        return customers;
    }

    private static int[][] allocate(int[] degrees) {
        int[][] rows = new int[degrees.length][];
        for (int i = 0; i < degrees.length; i++) {
            rows[i] = new int[degrees[i]];
        }
        return rows;
    }

    private static int[] toArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }
}
