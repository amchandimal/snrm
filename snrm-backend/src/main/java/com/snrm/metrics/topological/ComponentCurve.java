package com.snrm.metrics.topological;

import java.util.Random;

/**
 * The largest-connected-component curve, and the robustness index over it
 * ({@code ROBUSTNESS_RANDOM} / {@code ROBUSTNESS_TARGETED}).
 *
 * <blockquote>"Mean normalised largest-connected-component size over the removal sequence, as
 * nodes are removed randomly / by descending criticality (Schneider et al. 2011; Lou et al.
 * 2020)."</blockquote>
 *
 * <h2>The index</h2>
 *
 * <p>Remove the nodes of an <em>n</em>-node network one at a time in some order. After <em>k</em>
 * removals let <em>S(k)</em> be the number of nodes in the largest weakly connected component of
 * what is left. The index is
 *
 * <pre>
 *   R = (1/n) · Σ&nbsp;S(k) / S(0)          k = 1 … n
 * </pre>
 *
 * <p>— the robustness measure of Schneider et al. (2011) as adopted for supply networks by Lou et
 * al. (2020), which is the SLR source the metric catalog cites for the Rr/Rt pair (metric
 * definitions must not drift from the literature definitions they cite). <strong>The k = 0 term is
 * the normaliser, not a summand</strong>: the trapezoidal area under the whole curve differs from R
 * by exactly (S(0) − S(n)) / (2n·S(0)) — a constant 1/(2n) on a connected network — and an earlier
 * revision of this class computed that area, which made every figure quoted from this tool
 * incomparable with the papers it cites, and with the verification workbook that implements the
 * cited formula. Both are Riemann readings of the same curve; the literature's own discretisation
 * is the one a reader can check against a published number.
 *
 * <p>On a network whose intact form is a single weak component — S(0) = n — R lies in
 * {@code [0, (n−1)/2n]}, just under ½ for a network that stays whole until the last removal; a
 * network that fragments on the first removal contributes almost nothing. In general the ceiling is
 * {@code (2n − S(0) − 1)/2n}: a fragmented start <em>raises</em> it, because removals outside the
 * main component leave S(k) pinned at S(0) while k advances. Higher is more robust. Two deliberate
 * readings, both stated because Lou et al. differ: the component is the plain weakly connected
 * LCC, not the all-role LACC of their SLACC refinement; and the normaliser
 * is S(0), so a network that starts fragmented is measured by the decay of its main component
 * rather than punished twice for the fragments.
 *
 * <h2>Why the removals are re-added rather than removed</h2>
 *
 * <p>Deleting a node from a graph and asking for its components again is O(n + m) per step and
 * O(n·(n + m)) per curve. Adding them back in reverse order is not: components only ever merge, so a
 * union–find covers a whole curve in near-linear time. Every curve here is therefore computed
 * backwards — the last node removed is the first one added — and the answers are read off in
 * reverse. That is what keeps FR-04's two-second budget reachable when
 * {@code ROBUSTNESS_RANDOM} needs many curves.
 */
final class ComponentCurve {

    /**
     * Above this many nodes, {@code ROBUSTNESS_RANDOM} is sampled rather than enumerated exactly.
     *
     * <p>Exact enumeration visits every subset of nodes: 2<sup>16</sup> is 65,536 component
     * computations on graphs of at most sixteen nodes, which is milliseconds, and 2<sup>17</sup> is
     * twice that. The line has to fall somewhere; it falls where a research-scale hand-checkable
     * network still gets an exact, seed-free answer.
     */
    static final int EXACT_ENUMERATION_LIMIT = 16;

    /**
     * Removal orders sampled beyond that limit.
     *
     * <p>Each order is a whole curve, so this is 64 independent estimates of every point on it.
     * The quantity being estimated is a mean of bounded values and converges quickly; the residual
     * uncertainty is far below the differences between configurations that the comparison view
     * exists to show.
     */
    static final int SAMPLED_ORDERS = 64;

    /**
     * The seed the sampled orders are drawn from.
     *
     * <p>Fixed, not random. Reproducibility is a research-validity requirement, and a
     * structural metric that answered differently on each request would make a variant comparison
     * partly a comparison of seeds. Two networks of the same size are therefore removed in the same
     * sequence of positions, so the difference between their curves is the difference between their
     * topologies.
     */
    static final long SAMPLING_SEED = 20_260_727L;

    private ComponentCurve() {
    }

    /**
     * {@code S(k)} for {@code k = 0 … n}, given a removal order.
     *
     * @param order every node index exactly once, in the order they are removed
     * @return an array of length {@code n + 1}; entry k is the largest component after k removals
     */
    static int[] curve(GraphIndex index, int[] order) {
        int n = index.size();
        int[] largest = new int[n + 1];
        int[] parent = new int[n];
        int[] size = new int[n];
        boolean[] present = new boolean[n];
        int best = 0;

        largest[n] = 0;
        for (int k = n - 1; k >= 0; k--) {
            int added = order[k];
            present[added] = true;
            parent[added] = added;
            size[added] = 1;
            best = Math.max(best, 1);
            for (int neighbour : index.neighbours(added)) {
                if (present[neighbour]) {
                    best = Math.max(best, union(parent, size, added, neighbour));
                }
            }
            largest[k] = best;
        }
        return largest;
    }

    /** {@code R = Σ S(k) / (n · S(0))}, k = 1 … n. */
    static double robustness(int[] largest, int n) {
        if (n <= 0 || largest[0] <= 0) {
            return 0;
        }
        double sum = 0;
        for (int k = 1; k <= n; k++) {
            sum += largest[k];
        }
        return sum / ((double) n * largest[0]);
    }

    /** The same, for a curve of averaged (and so fractional) component sizes. */
    static double robustness(double[] largest, int n) {
        if (n <= 0 || largest[0] <= 0) {
            return 0;
        }
        double sum = 0;
        for (int k = 1; k <= n; k++) {
            sum += largest[k];
        }
        return sum / (n * largest[0]);
    }

    /**
     * The index for a uniformly random removal order — {@code ROBUSTNESS_RANDOM}.
     *
     * <p>R is a linear function of the points {@code S(k)}, so the expected R is R evaluated on the
     * expected curve, and the expected {@code S(k)} depends only on <em>which</em> k nodes are gone
     * and not on the order they went in. That is what makes an exact answer possible for a small
     * network: averaging {@code S} over all subsets of each size gives precisely the value an
     * infinite number of random removal orders would converge to, with no seed involved.
     * ({@code S(0)} is the intact network's largest component and is the same for every order.)
     *
     * <p>Beyond {@link #EXACT_ENUMERATION_LIMIT} nodes there are too many subsets to enumerate, and
     * the same expectation is estimated from {@link #SAMPLED_ORDERS} seeded removal orders. The two
     * modes answer the same question to different precision; a network that crosses the limit
     * between two variants will show a small step, which is the price of an exact answer at the
     * scale where an exact answer is affordable.
     */
    static double expectedRobustness(GraphIndex index) {
        int n = index.size();
        if (n == 0) {
            return 0;
        }
        return n <= EXACT_ENUMERATION_LIMIT
                ? exactExpectedRobustness(index)
                : sampledExpectedRobustness(index);
    }

    /** Averages {@code S(k)} over every subset of every size, then applies the formula once. */
    private static double exactExpectedRobustness(GraphIndex index) {
        int n = index.size();
        double[] totalByRemoved = new double[n + 1];
        long[] subsetsByRemoved = new long[n + 1];

        int masks = 1 << n;
        int[] parent = new int[n];
        int[] size = new int[n];
        boolean[] present = new boolean[n];
        for (int mask = 0; mask < masks; mask++) {
            int surviving = Integer.bitCount(mask);
            totalByRemoved[n - surviving] += largestComponent(index, mask, parent, size, present);
            subsetsByRemoved[n - surviving]++;
        }

        double[] expected = new double[n + 1];
        for (int k = 0; k <= n; k++) {
            expected[k] = subsetsByRemoved[k] == 0 ? 0 : totalByRemoved[k] / subsetsByRemoved[k];
        }
        return robustness(expected, n);
    }

    /** Averages the index over sampled removal orders. */
    private static double sampledExpectedRobustness(GraphIndex index) {
        int n = index.size();
        Random random = new Random(SAMPLING_SEED);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        double total = 0;
        for (int sample = 0; sample < SAMPLED_ORDERS; sample++) {
            shuffle(order, random);
            total += robustness(curve(index, order), n);
        }
        return total / SAMPLED_ORDERS;
    }

    /** Largest weakly connected component among the nodes whose bit is set in {@code mask}. */
    private static int largestComponent(GraphIndex index, int mask, int[] parent, int[] size,
            boolean[] present) {
        int n = index.size();
        int best = 0;
        for (int i = 0; i < n; i++) {
            present[i] = (mask & (1 << i)) != 0;
            if (present[i]) {
                parent[i] = i;
                size[i] = 1;
                best = 1;
            }
        }
        for (int i = 0; i < n; i++) {
            if (!present[i]) {
                continue;
            }
            for (int neighbour : index.neighbours(i)) {
                if (present[neighbour]) {
                    best = Math.max(best, union(parent, size, i, neighbour));
                }
            }
        }
        return best;
    }

    /** Fisher–Yates, in place. */
    private static void shuffle(int[] order, Random random) {
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
    }

    /** Union by size with path halving. @return the size of the component the two now share */
    private static int union(int[] parent, int[] size, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA == rootB) {
            return size[rootA];
        }
        if (size[rootA] < size[rootB]) {
            int swap = rootA;
            rootA = rootB;
            rootB = swap;
        }
        parent[rootB] = rootA;
        size[rootA] += size[rootB];
        return size[rootA];
    }

    private static int find(int[] parent, int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }
}
