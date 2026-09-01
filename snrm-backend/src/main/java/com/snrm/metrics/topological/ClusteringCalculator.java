package com.snrm.metrics.topological;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code CLUSTERING} — a standard graph statistic on the directed network.
 *
 * <p>The average local clustering coefficient (Watts–Strogatz): for each node, how many of the pairs
 * of its neighbours are themselves connected, averaged over the nodes.
 *
 * <pre>
 *   C(v) = ( links among the neighbours of v ) / ( k(v) · (k(v) − 1) / 2 )
 *   CLUSTERING = ( Σ C(v) ) / n
 * </pre>
 *
 * <p>In a supply network it measures redundancy of the local kind that matters under disruption: a
 * DC whose two upstream plants also supply each other, a pair of DCs that both serve a customer and
 * can transship between themselves. Those triangles are alternative routes that survive the loss of
 * one member.
 *
 * <h2>Direction is dropped here, and only here</h2>
 *
 * <p>Neighbours are taken with arc directions ignored, and a triangle counts whichever way its arcs
 * point. That is a deliberate departure from "on the directed network", made because the directed
 * alternative measures nothing on the networks this tool exists to model: a directed triangle
 * requires a cycle, an echelon-respecting supply network has none, and a directed clustering
 * coefficient would therefore report exactly 0 for every well-formed network — a metric that cannot
 * vary is not a metric. The undirected reading does vary, and it varies with the thing the metric is
 * for: {@code PLANT→DC-1}, {@code PLANT→DC-2}, {@code DC-1→DC-2} is a real alternative path even
 * though it is not a cycle.
 *
 * <p>A node with fewer than two neighbours has no pair of them and contributes 0 — the usual
 * convention, and the one that keeps the average over <em>all</em> nodes rather than over a
 * subset whose membership would itself change with an edit. Every leaf of the network therefore
 * pulls the average down, which is why the value is read comparatively.
 */
@Component
@Order(60)
public class ClusteringCalculator implements MetricCalculator {

    public static final String CODE = "CLUSTERING";

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
        GraphIndex index = GraphIndex.of(ctx);
        int n = index.size();
        if (n == 0) {
            return List.of(MetricValue.network(CODE, 0));
        }

        // Which unordered pairs are joined at all. Built once: "are these two neighbours of v also
        // neighbours of each other" is asked O(k²) times per node and has to be a lookup.
        Set<Long> joined = new HashSet<>();
        for (int e = 0; e < index.linkCount(); e++) {
            int source = index.linkSource(e);
            int target = index.linkTarget(e);
            if (source >= 0 && target >= 0 && source != target) {
                joined.add(pairKey(source, target, n));
            }
        }

        double total = 0;
        for (int v = 0; v < n; v++) {
            // A set, because the adjacency lists an arc from each end: a pair joined both ways — a
            // lateral A→B beside a reverse-logistics B→A, both legal — would otherwise
            // count as two neighbours.
            Set<Integer> neighbours = new HashSet<>();
            for (int neighbour : index.neighbours(v)) {
                if (neighbour != v) {
                    neighbours.add(neighbour);
                }
            }
            int degree = neighbours.size();
            if (degree < 2) {
                continue;
            }
            int[] members = new int[degree];
            int fill = 0;
            for (int neighbour : neighbours) {
                members[fill++] = neighbour;
            }
            int connectedPairs = 0;
            for (int a = 0; a < degree; a++) {
                for (int b = a + 1; b < degree; b++) {
                    if (joined.contains(pairKey(members[a], members[b], n))) {
                        connectedPairs++;
                    }
                }
            }
            double possiblePairs = degree * (degree - 1) / 2.0;
            total += connectedPairs / possiblePairs;
        }
        return List.of(MetricValue.network(CODE, total / n));
    }

    /** Order-independent key for an unordered pair of node indices. */
    private static long pairKey(int a, int b, int n) {
        return a < b ? (long) a * n + b : (long) b * n + a;
    }
}
