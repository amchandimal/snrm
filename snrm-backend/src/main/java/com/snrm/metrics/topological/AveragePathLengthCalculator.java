package com.snrm.metrics.topological;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * {@code AVG_PATH} — a standard graph statistic on the directed network.
 *
 * <p>The characteristic path length of the directed graph: the mean number of arcs on a shortest
 * directed path, averaged over the ordered pairs that have one.
 *
 * <pre>
 *   AVG_PATH = ( Σ d(u,v) ) / |{ (u,v) : u ≠ v, v reachable from u }|
 * </pre>
 *
 * <h2>Two readings this takes, and why</h2>
 *
 * <p><strong>Unreachable pairs are excluded, not counted as infinite or as zero.</strong> A supply
 * network is a layered directed graph in which most ordered pairs have no path at all — no customer
 * reaches a supplier, and no supplier reaches another. Counting those pairs as infinite makes the
 * metric infinite for every real network; counting them as zero rewards a network for falling apart,
 * since a disconnected pair would shorten the average. Averaging over connected pairs is the
 * standard treatment and is the only one that keeps the number readable as "how many hops material
 * typically travels". It does mean the metric must be read beside {@code SPOF_COUNT} and the
 * robustness indices, which is what says how much of the network the average covers.
 *
 * <p><strong>Distance is hops, not lead time and not capacity.</strong> The snapshot's JGraphT graph
 * is weighted by per-period capacity (see {@code NetworkGraph.jgrapht()}), which is a throughput and
 * not a distance — summing it along a path would produce a number with no meaning. Lead time would
 * be a defensible distance, but a different metric: it would answer "how long does material take"
 * rather than "how many echelons does it cross", and it would change with the network's period
 * rather than with its structure. This is a structural statistic, so it counts arcs.
 * The traversal is therefore a breadth-first search from every node rather than a weighted
 * shortest-path algorithm.
 *
 * <p>A network in which nothing reaches anything — no links, or one node — reports 0.
 */
@Component
@Order(50)
public class AveragePathLengthCalculator implements MetricCalculator {

    public static final String CODE = "AVG_PATH";

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
        long pairs = 0;
        long total = 0;

        int[] distance = new int[n];
        int[] queue = new int[n];
        for (int source = 0; source < n; source++) {
            Arrays.fill(distance, -1);
            int head = 0;
            int tail = 0;
            distance[source] = 0;
            queue[tail++] = source;
            while (head < tail) {
                int current = queue[head++];
                for (int next : index.successors(current)) {
                    if (distance[next] < 0) {
                        distance[next] = distance[current] + 1;
                        queue[tail++] = next;
                        pairs++;
                        total += distance[next];
                    }
                }
            }
        }
        return List.of(MetricValue.network(CODE, pairs == 0 ? 0 : (double) total / pairs));
    }
}
