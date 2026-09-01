package com.snrm.metrics.topological;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricScope;
import com.snrm.metrics.MetricValue;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code SPOF_NODE_COUNT}, {@code SPOF_ARC_COUNT} and {@code SPOF_COUNT}: nodes and arcs
 * whose single removal cuts some customer off from all supply, counted separately and together.
 *
 * <p>Three of the tests below are about what the counts deliberately do <em>not</em> include — a
 * customer disconnecting itself, a customer that was never connected, and an element whose loss costs
 * capacity but not connectivity. Each is a place the numbers would silently inflate.
 *
 * <p>Every case asserts all three codes rather than the total alone. The split is the part a reader
 * acts on — an indispensable facility and an indispensable lane are different problems — and a
 * regression that moved one element from the node column to the arc column would leave the total
 * untouched and go unseen. The identity is asserted alongside: the total is the sum of the halves,
 * so nothing may be counted twice or dropped between them.
 */
class SpofCountCalculatorTest {

    private static double spofNodes(NetworkGraph graph) {
        return new SpofNodeCountCalculator().compute(MetricContext.of(graph)).get(0).value();
    }

    private static double spofArcs(NetworkGraph graph) {
        return new SpofArcCountCalculator().compute(MetricContext.of(graph)).get(0).value();
    }

    private static double spofCount(NetworkGraph graph) {
        return new SpofCountCalculator().compute(MetricContext.of(graph)).get(0).value();
    }

    /** The split and the identity that binds it, in one assertion. */
    private static void assertSpof(NetworkGraph graph, double nodes, double arcs) {
        assertThat(spofNodes(graph)).as("SPOF_NODE_COUNT").isEqualTo(nodes);
        assertThat(spofArcs(graph)).as("SPOF_ARC_COUNT").isEqualTo(arcs);
        assertThat(spofCount(graph)).as("SPOF_COUNT is the sum of the two").isEqualTo(nodes + arcs);
    }

    @Test
    @DisplayName("in a chain every node and arc but the customer is a single point of failure")
    void chainIsAllSpofs() {
        // SUP-1, PLANT-1 and both arcs — four. CUST-1 is not one of them: removing it leaves no
        // customer to be stranded, and a node whose only consequence is its own disappearance is
        // the failure rather than a point of it.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("SUP-1").plant("PLANT-1").customer("CUST-1", 10)
                .link("SUP-1", "PLANT-1").link("PLANT-1", "CUST-1")
                .build();

        assertSpof(graph, 2, 2);
    }

    @Test
    @DisplayName("a second route removes the intermediate nodes and arcs from the count")
    void redundancyLeavesOnlyTheSupplier() {
        NetworkGraph graph = GraphFixtures.network()
                .supplier("SUP-1").dc("DC-1").dc("DC-2").customer("CUST-1", 10)
                .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                .link("DC-1", "CUST-1").link("DC-2", "CUST-1")
                .build();

        // Only SUP-1: it is the sole origin, and every other element has an alternative. The zero
        // in the arc column is the finding a reader acts on — duplicating a lane would buy nothing
        // here, and a second supply origin is the whole of the remedy.
        assertSpof(graph, 1, 0);
    }

    @Test
    @DisplayName("a fully redundant network has none, which is the property being looked for")
    void fullyRedundantNetworkIsZero() {
        NetworkGraph graph = GraphFixtures.network()
                .supplier("SUP-1").supplier("SUP-2").dc("DC-1").dc("DC-2").customer("CUST-1", 10)
                .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                .link("SUP-2", "DC-1").link("SUP-2", "DC-2")
                .link("DC-1", "CUST-1").link("DC-2", "CUST-1")
                .build();

        assertSpof(graph, 0, 0);
    }

    @Test
    @DisplayName("the two halves are different figures, and neither follows from the other")
    void theHalvesAreNotDerivableFromEachOther() {
        // One supplier feeding two DCs, each serving a customer of its own. Three facilities are
        // indispensable and four lanes are, and 7 says neither. The arc figure being the larger is
        // the finding: the network has more single-lane exposure than single-site exposure, so
        // duplicating the routes buys more here than duplicating the DCs would.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("SUP-1").dc("DC-1").dc("DC-2")
                .customer("CUST-1", 10).customer("CUST-2", 5)
                .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                .link("DC-1", "CUST-1").link("DC-2", "CUST-2")
                .build();

        assertSpof(graph, 3, 4);
    }

    @Test
    @DisplayName("a customer that was never supplied is not blamed on every element in turn")
    void ignoresACustomerThatWasAlreadyDisconnected() {
        // CUST-2 has no inbound arc, so it is unsupplied before anything is removed. Without the
        // "supplied in the intact network" guard, every element would count — the network would
        // report 2 nodes and 1 arc as points of failure for a disconnection none of them caused.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("SUP-1").customer("CUST-1", 10).customer("CUST-2", 5)
                .link("SUP-1", "CUST-1")
                .build();

        // SUP-1, and the arc into CUST-1.
        assertSpof(graph, 1, 1);
    }

    @Test
    @DisplayName("it asks about connectivity, not capacity")
    void narrowAlternativeRouteIsNotASpof() {
        // DC-1 carries 9 of the 10 and DC-2 only 1, so losing DC-1 costs 90% of serviceable demand
        // — NODE_CRITICALITY says so. It is still not a single point of failure, because a path
        // survives. The two metrics answer different questions and are meant to be read together.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("SUP-1").dc("DC-1").dc("DC-2").customer("CUST-1", 10)
                .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                .link("DC-1", "CUST-1", 9.0).link("DC-2", "CUST-1", 1.0)
                .build();

        assertSpof(graph, 1, 0); // SUP-1 only

        double criticalityOfDc1 = new NodeCriticalityCalculator()
                .compute(MetricContext.of(graph)).stream()
                .filter(value -> "DC-1".equals(value.scopeName()))
                .findFirst().orElseThrow().value();
        assertThat(criticalityOfDc1).isEqualTo(0.9, within(1e-9));
    }

    @Test
    @DisplayName("an empty network has none")
    void emptyNetworkIsZero() {
        assertSpof(GraphFixtures.network().build(), 0, 0);
    }

    @Test
    @DisplayName("each of the three is one network-scoped count, not a row per element")
    void eachProducesOneNetworkScopedCount() {
        NetworkGraph graph = GraphFixtures.verificationNetwork();

        assertThat(List.<MetricCalculator>of(new SpofNodeCountCalculator(),
                new SpofArcCountCalculator(), new SpofCountCalculator()))
                .allSatisfy(calculator -> {
                    List<MetricValue> values = calculator.compute(MetricContext.of(graph));
                    assertThat(values).hasSize(1);
                    assertThat(values.get(0).scope()).isEqualTo(MetricScope.NETWORK);
                });

        // docs/metric-verification.md section 8: three nodes and three arcs.
        assertSpof(graph, 3, 3);
    }

    @Test
    @DisplayName("all three rank lower as better, so the comparison matrix highlights a winner")
    void allThreeAreLowerIsBetter() {
        assertThat(List.<MetricCalculator>of(new SpofNodeCountCalculator(),
                new SpofArcCountCalculator(), new SpofCountCalculator()))
                .allSatisfy(calculator -> assertThat(calculator.direction())
                        .isEqualTo(MetricDirection.LOWER_IS_BETTER));
    }

    @Test
    @DisplayName("the census is computed once per context, not once per calculator")
    void theThreeShareOneCensus() {
        // The whole reason SpofAnalysis exists: the test behind the three codes is one traversal per
        // node plus one per arc, and running it three times would triple the cost of the second most
        // expensive structural metric (FR-04). Asserted on the memoised instance rather
        // than on a timing, which would be flaky.
        MetricContext ctx = MetricContext.of(GraphFixtures.verificationNetwork());

        SpofAnalysis first = SpofAnalysis.of(ctx);
        new SpofNodeCountCalculator().compute(ctx);
        new SpofArcCountCalculator().compute(ctx);
        new SpofCountCalculator().compute(ctx);

        assertThat(SpofAnalysis.of(ctx)).isSameAs(first);
    }
}
