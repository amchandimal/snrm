package com.snrm.metrics;

import com.snrm.metrics.topological.AveragePathLengthCalculator;
import com.snrm.metrics.topological.ClusteringCalculator;
import com.snrm.metrics.topological.DensityCalculator;
import com.snrm.metrics.topological.NodeCriticalityCalculator;
import com.snrm.metrics.topological.RobustnessRandomCalculator;
import com.snrm.metrics.topological.RobustnessTargetedCalculator;
import com.snrm.metrics.topological.SpofArcCountCalculator;
import com.snrm.metrics.topological.SpofCountCalculator;
import com.snrm.metrics.topological.SpofNodeCountCalculator;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The whole topological suite against the network of {@code docs/metric-verification.md}.
 *
 * <p><strong>This test's expected values are not derived from the implementation.</strong> Every one
 * of them is worked out by hand in that document, one arithmetic step at a time, from the definitions
 * — the fractions below are transcribed from its §9 and each carries the section that
 * derives it. That is what makes this a verification rather than a regression test: if the code and
 * the document disagree, one of them is wrong and the document is the one a reader can check.
 *
 * <p>The risk this addresses is a direct one: metric definitions drifting from literature
 * definitions, weakening thesis claims. A suite whose only specification is its own output cannot
 * drift, because there is nothing for it to drift from.
 *
 * <p>The network is six nodes and seven links; {@code samples/metric-verification-6-node/} is the
 * same network in the canonical import schema, so the same numbers can be obtained end-to-end
 * through the API (`api-tests.http` requests 57–58).
 */
class VerificationNetworkTest {

    /**
     * The tolerance §9 of the document specifies.
     *
     * <p>Most of these values are non-terminating rationals — 7/30, 21/13, 77/216 — so they are not
     * exactly representable as doubles and comparing for equality would be asserting a property of
     * IEEE 754 rather than of the metric. 1e-9 is far tighter than any drift these computations can
     * produce and far looser than the last-bit differences summation order causes.
     */
    private static final double TOLERANCE = 1e-9;

    private static final List<MetricCalculator> SUITE = List.of(
            new DensityCalculator(),
            new SpofNodeCountCalculator(),
            new SpofArcCountCalculator(),
            new SpofCountCalculator(),
            new AveragePathLengthCalculator(),
            new ClusteringCalculator(),
            new NodeCriticalityCalculator(),
            new RobustnessRandomCalculator(),
            new RobustnessTargetedCalculator());

    private static List<MetricValue> values;

    @BeforeAll
    static void computeTheSuite() {
        NetworkGraph graph = GraphFixtures.verificationNetwork();
        values = new MetricCalculatorRegistry(SUITE)
                .compute(MetricKind.TOPOLOGICAL, MetricContext.of(graph));
    }

    // ------------------------------------------------------------------- network-scoped

    @Test
    @DisplayName("DENSITY is 7/30 — seven arcs of the thirty ordered pairs (§3)")
    void density() {
        assertThat(network("DENSITY")).isEqualTo(7.0 / 30, within(TOLERANCE));
    }

    @Test
    @DisplayName("AVG_PATH is 21/13 — 21 hops over the 13 connected ordered pairs (§4)")
    void averagePathLength() {
        // 13, not 30: the metric averages over the pairs that have a directed path, which in a
        // layered network is a minority of them. Counting the rest as infinite would make the
        // metric infinite for every realistic network.
        assertThat(network("AVG_PATH")).isEqualTo(21.0 / 13, within(TOLERANCE));
    }

    @Test
    @DisplayName("CLUSTERING is 7/18 — (0 + 1/3 + 1/3 + 2/3 + 0 + 1) / 6 (§5)")
    void clustering() {
        assertThat(network("CLUSTERING")).isEqualTo(7.0 / 18, within(TOLERANCE));
    }

    @Test
    @DisplayName("ROBUSTNESS_RANDOM is 77/216 — the exact expectation over removal orders (§7.2)")
    void robustnessRandom() {
        assertThat(network("ROBUSTNESS_RANDOM")).isEqualTo(77.0 / 216, within(TOLERANCE));
    }

    @Test
    @DisplayName("ROBUSTNESS_TARGETED is 7/18 (§7.1)")
    void robustnessTargeted() {
        assertThat(network("ROBUSTNESS_TARGETED")).isEqualTo(7.0 / 18, within(TOLERANCE));
    }

    @Test
    @DisplayName("SPOF_NODE_COUNT is 3 — SUP-1, PLANT-1 and DC-1 (§8.1)")
    void spofNodeCount() {
        assertThat(network("SPOF_NODE_COUNT")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("SPOF_ARC_COUNT is 3 — arcs a, b and e (§8.2)")
    void spofArcCount() {
        assertThat(network("SPOF_ARC_COUNT")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("SPOF_COUNT is 6 — the two halves, and nothing counted twice (§8)")
    void spofCount() {
        assertThat(network("SPOF_COUNT")).isEqualTo(6.0);
        assertThat(network("SPOF_COUNT"))
                .isEqualTo(network("SPOF_NODE_COUNT") + network("SPOF_ARC_COUNT"));
    }

    @Test
    @DisplayName("targeted robustness exceeds random here, and that is the finding, not a defect")
    void targetedExceedsRandomOnThisNetwork() {
        // §7.3. Criticality ranks by flow, so PLANT-1 and SUP-1 lead the removal order — and both
        // are structurally peripheral, so two removals in the largest component still holds four
        // of the six nodes. Random removal hits DC-1, the structural hub, one time in six at the
        // first step. The network is fragile in service terms and cohesive in structural ones,
        // which is exactly why both are reported.
        assertThat(network("ROBUSTNESS_TARGETED")).isGreaterThan(network("ROBUSTNESS_RANDOM"));
    }

    // ---------------------------------------------------------------------- per-node

    @Nested
    @DisplayName("NODE_CRITICALITY (§6)")
    class NodeCriticality {

        @Test
        @DisplayName("the sole supplier and the sole plant are both 1 — without either, nothing is served")
        void singlePointsOfSupply() {
            assertThat(node("SUP-1")).isEqualTo(1.0);
            assertThat(node("PLANT-1")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("SUP-1 scores 1 because the origin set does not follow the removal")
        void originSetIsFixedOnTheIntactGraph() {
            // The subtle rule of §2. If origins were recomputed after the removal, PLANT-1 — now
            // without an inbound arc — would be promoted to a supply origin and the network would
            // still serve all 65, reporting that losing a sole supplier costs nothing.
            assertThat(node("SUP-1")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("DC-1 is 8/13: CUST-1 has no other route, CUST-2 does")
        void partialLoss() {
            // (65 − 25) / 65. Removing DC-1 strands CUST-1's 40 entirely; CUST-2's 25 still arrives
            // through PLANT-1 → DC-2 → CUST-2.
            assertThat(node("DC-1")).isEqualTo(8.0 / 13, within(TOLERANCE));
        }

        @Test
        @DisplayName("DC-2 is exactly 0 — arc DC-1 → CUST-2 covers everything it carries")
        void fullyRedundantNodeIsExactlyZero() {
            // Exactly, not approximately: 0 is a claim a reader will quote, and a criticality of
            // 1.4e-16 would rank a redundant node above an equally redundant one in the
            // criticality table. ServiceableDemand.snap is what guarantees it.
            assertThat(node("DC-2")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("a customer's criticality is its own share of demand")
        void customersScoreTheirDemandShare() {
            // CUST-1 is 40/65 and CUST-2 is 25/65. Removing a customer removes the demand it
            // existed to represent, so the drop is that demand — the definition working correctly,
            // and a different quantity from DC-1's identical 8/13, which is about routing.
            assertThat(node("CUST-1")).isEqualTo(40.0 / 65, within(TOLERANCE));
            assertThat(node("CUST-2")).isEqualTo(25.0 / 65, within(TOLERANCE));
        }

        @Test
        @DisplayName("one row per node, in snapshot order, each naming its node")
        void oneRowPerNodeInSnapshotOrder() {
            List<MetricValue> rows = rows("NODE_CRITICALITY");

            assertThat(rows).hasSize(6);
            assertThat(rows).extracting(MetricValue::scopeName)
                    .containsExactly("SUP-1", "PLANT-1", "DC-1", "DC-2", "CUST-1", "CUST-2");
            assertThat(rows).extracting(MetricValue::scope)
                    .containsOnly(MetricScope.NODE);
            assertThat(rows).extracting(MetricValue::scopeId)
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        }
    }

    // ------------------------------------------------------------------ suite as a whole

    @Nested
    @DisplayName("the shape of the suite")
    class SuiteShape {

        @Test
        @DisplayName("fourteen values: eight network-scoped and six per-node criticalities")
        void producesFourteenValues() {
            assertThat(values).hasSize(14);
            assertThat(values).filteredOn(value -> value.scope() == MetricScope.NETWORK).hasSize(8);
            assertThat(values).filteredOn(value -> value.scope() == MetricScope.NODE).hasSize(6);
        }

        @Test
        @DisplayName("no confidence interval and no display unit anywhere")
        void topologicalValuesAreExactAndDimensionless() {
            // A CI is a property of aggregating replications, which topological metrics do not do;
            // a display unit belongs to a metric counted in periods, and nothing in this
            // suite is.
            assertThat(values).allSatisfy(value -> {
                assertThat(value.ciLow()).isNull();
                assertThat(value.ciHigh()).isNull();
                assertThat(value.displayUnit()).isNull();
            });
        }

        @Test
        @DisplayName("a network-scoped value carries no scope id or name")
        void networkScopedValuesAreUnscoped() {
            assertThat(values)
                    .filteredOn(value -> value.scope() == MetricScope.NETWORK)
                    .allSatisfy(value -> {
                        assertThat(value.scopeId()).isNull();
                        assertThat(value.scopeName()).isNull();
                    });
        }

        @Test
        @DisplayName("@Order puts the calculators in the suite order")
        void springOrderMatchesTheSuiteTable() {
            // Spring sorts an injected List<MetricCalculator> with this comparator, so this is the
            // order the registry will really see. Asserted from a shuffled list, since the point is
            // that the annotations decide it and not the declaration order above.
            List<MetricCalculator> shuffled = new ArrayList<>(SUITE);
            Collections.reverse(shuffled);
            AnnotationAwareOrderComparator.sort(shuffled);

            // The order leads with the four figures a configuration is judged structurally on —
            // the density, then the single points of failure as nodes, as arcs and as their total
            // — because that is the order every surface lists them in, and the registry
            // is where that order is decided.
            assertThat(shuffled).extracting(MetricCalculator::code).containsExactly(
                    "DENSITY", "SPOF_NODE_COUNT", "SPOF_ARC_COUNT", "SPOF_COUNT",
                    "AVG_PATH", "CLUSTERING", "NODE_CRITICALITY",
                    "ROBUSTNESS_RANDOM", "ROBUSTNESS_TARGETED");
        }

        @Test
        @DisplayName("every calculator in the suite is topological")
        void allTopological() {
            assertThat(SUITE).allSatisfy(calculator ->
                    assertThat(calculator.kind()).isEqualTo(MetricKind.TOPOLOGICAL));
        }
    }

    // -------------------------------------------------------------------------- helpers

    private static double network(String code) {
        return rows(code).stream()
                .filter(value -> value.scope() == MetricScope.NETWORK)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no NETWORK-scoped " + code + " was produced"))
                .value();
    }

    private static double node(String name) {
        return rows("NODE_CRITICALITY").stream()
                .filter(value -> name.equals(value.scopeName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no criticality row for " + name))
                .value();
    }

    private static List<MetricValue> rows(String code) {
        return values.stream().filter(value -> value.code().equals(code)).toList();
    }
}
