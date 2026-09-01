package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricValue;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import com.snrm.network.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code NODE_CRITICALITY} and the maximum-flow computation behind it.
 *
 * <blockquote>"Relative drop in max serviceable demand when the node is removed (computed for every
 * node)."</blockquote>
 *
 * <p>Split in two: {@link MaximumServiceableDemand} pins <em>D</em> itself — which capacity binds,
 * where supply enters — and {@link Criticality} pins the ratio built from it. The first is where a
 * modelling error would live; the second is where an arithmetic one would.
 */
class NodeCriticalityCalculatorTest {

    private static double criticality(NetworkGraph graph, String nodeName) {
        return values(graph).stream()
                .filter(value -> nodeName.equals(value.scopeName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no criticality row for " + nodeName))
                .value();
    }

    private static List<MetricValue> values(NetworkGraph graph) {
        return new NodeCriticalityCalculator().compute(MetricContext.of(graph));
    }

    private static double demandServed(NetworkGraph graph) {
        return ServiceableDemand.maxServiceableDemand(
                GraphIndex.of(MetricContext.of(graph)), -1);
    }

    @Nested
    @DisplayName("maximum serviceable demand")
    class MaximumServiceableDemand {

        @Test
        @DisplayName("an uncapacitated chain serves all of the demand")
        void demandLimited() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").plant("PLANT-1").customer("CUST-1", 10)
                    .link("SUP-1", "PLANT-1").link("PLANT-1", "CUST-1")
                    .build();

            assertThat(demandServed(graph)).isEqualTo(10.0);
        }

        @Test
        @DisplayName("a node's own capacity binds — which is what splitting the node is for")
        void nodeCapacityBinds() {
            // Every arc is unconstrained; only PLANT-1's throughput of 6 stands between supply and
            // a demand of 10. Without the in/out split there would be nowhere to apply it and the
            // answer would be 10.
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").plant("PLANT-1", 6.0).customer("CUST-1", 10)
                    .link("SUP-1", "PLANT-1").link("PLANT-1", "CUST-1")
                    .build();

            assertThat(demandServed(graph)).isEqualTo(6.0);
        }

        @Test
        @DisplayName("an arc's capacity binds")
        void arcCapacityBinds() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").customer("CUST-1", 10)
                    .link("SUP-1", "CUST-1", 4.0)
                    .build();

            assertThat(demandServed(graph)).isEqualTo(4.0);
        }

        @Test
        @DisplayName("two narrow arcs add up")
        void parallelRoutesSum() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").dc("DC-1").dc("DC-2").customer("CUST-1", 10)
                    .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                    .link("DC-1", "CUST-1", 6.0).link("DC-2", "CUST-1", 6.0)
                    .build();

            // 6 + 6 = 12 of capacity against 10 of demand, so demand is what binds.
            assertThat(demandServed(graph)).isEqualTo(10.0);
        }

        @Test
        @DisplayName("a network modelled from the factory gate has the plant as its origin")
        void plantWithNoInboundIsAnOrigin() {
            // Import accepts a network with no SUPPLIER row. The origin rule — supply-side, no
            // inbound arc — makes the plant the point where material enters, rather than leaving
            // the network with no source and every metric at zero.
            NetworkGraph graph = GraphFixtures.network()
                    .plant("PLANT-1").customer("CUST-1", 10)
                    .link("PLANT-1", "CUST-1")
                    .build();

            assertThat(demandServed(graph)).isEqualTo(10.0);
        }

        @Test
        @DisplayName("a network with no supply-side node serves nothing")
        void noOriginServesNothing() {
            // DCs stock and forward; a network of DCs and customers has no replenishment at all.
            NetworkGraph graph = GraphFixtures.network()
                    .dc("DC-1").customer("CUST-1", 10)
                    .link("DC-1", "CUST-1")
                    .build();

            assertThat(demandServed(graph)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("a network with no demand serves nothing")
        void noDemandServesNothing() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").customer("CUST-1", 0)
                    .link("SUP-1", "CUST-1")
                    .build();

            assertThat(demandServed(graph)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("demand entered on a plant is ignored, as the importer warns it will be")
        void demandOnANonCustomerIsIgnored() {
            // The importer warns about exactly this row (DEMAND_ON_NON_CUSTOMER); the engine has to
            // agree with the warning.
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1")
                    .node("PLANT-1", NodeType.PLANT, null, 99)
                    .customer("CUST-1", 10)
                    .link("SUP-1", "PLANT-1").link("PLANT-1", "CUST-1")
                    .build();

            assertThat(demandServed(graph)).isEqualTo(10.0);
        }
    }

    @Nested
    @DisplayName("criticality")
    class Criticality {

        @Test
        @DisplayName("every node of a chain is critical — there is no second route to anything")
        void chainIsAllOnes() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").plant("PLANT-1").customer("CUST-1", 10)
                    .link("SUP-1", "PLANT-1").link("PLANT-1", "CUST-1")
                    .build();

            assertThat(criticality(graph, "SUP-1")).isEqualTo(1.0);
            assertThat(criticality(graph, "PLANT-1")).isEqualTo(1.0);
            // The customer too: it is 100% of the demand, so removing it removes all of it.
            assertThat(criticality(graph, "CUST-1")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("removing a sole supplier does not promote the plant behind it into an origin")
        void originSetIsFixed() {
            // The rule that makes the previous test's first assertion mean anything. If origins
            // were recomputed on the reduced graph, PLANT-1 would become one the moment SUP-1 went
            // and the supplier's criticality would be 0.
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").plant("PLANT-1").customer("CUST-1", 10)
                    .link("SUP-1", "PLANT-1").link("PLANT-1", "CUST-1")
                    .build();

            assertThat(criticality(graph, "SUP-1")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a fully redundant node is exactly 0")
        void redundantNodesAreZero() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").dc("DC-1").dc("DC-2").customer("CUST-1", 10)
                    .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                    .link("DC-1", "CUST-1").link("DC-2", "CUST-1")
                    .build();

            // Exactly 0, not 1e-16: this is a claim a reader quotes, and a near-zero would sort a
            // redundant node above an equally redundant one in a ranked list.
            assertThat(criticality(graph, "DC-1")).isEqualTo(0.0);
            assertThat(criticality(graph, "DC-2")).isEqualTo(0.0);
            assertThat(criticality(graph, "SUP-1")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a partly redundant node scores the fraction of demand its loss costs")
        void partialRedundancy() {
            // Each arm can carry 6 of the 10. Losing one arm leaves 6, so the drop is 4/10.
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").dc("DC-1").dc("DC-2").customer("CUST-1", 10)
                    .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                    .link("DC-1", "CUST-1", 6.0).link("DC-2", "CUST-1", 6.0)
                    .build();

            assertThat(criticality(graph, "DC-1")).isEqualTo(0.4, within(1e-12));
            assertThat(criticality(graph, "DC-2")).isEqualTo(0.4, within(1e-12));
        }

        @Test
        @DisplayName("customers score their share of total demand")
        void customerSharesSumToOne() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").customer("BIG", 75).customer("SMALL", 25)
                    .link("SUP-1", "BIG").link("SUP-1", "SMALL")
                    .build();

            assertThat(criticality(graph, "BIG")).isEqualTo(0.75, within(1e-12));
            assertThat(criticality(graph, "SMALL")).isEqualTo(0.25, within(1e-12));
        }

        @Test
        @DisplayName("a network that can serve nothing reports 0 everywhere rather than NaN")
        void unservableNetworkIsAllZero() {
            // 0/0 has no reading that is not invented, and a NaN would propagate into the
            // robustness ordering and the node-size encoding.
            NetworkGraph graph = GraphFixtures.network()
                    .dc("DC-1").customer("CUST-1", 10)
                    .link("DC-1", "CUST-1")
                    .build();

            assertThat(values(graph)).hasSize(2);
            assertThat(values(graph)).allSatisfy(value -> assertThat(value.value()).isZero());
        }

        @Test
        @DisplayName("one row per node, in snapshot order, even where the value is 0")
        void oneRowPerNode() {
            // The per-node table and the node-size encoding both read a row per node; a
            // missing row would have to be interpreted rather than displayed.
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").dc("DC-1").dc("DC-2").customer("CUST-1", 10)
                    .link("SUP-1", "DC-1").link("SUP-1", "DC-2")
                    .link("DC-1", "CUST-1").link("DC-2", "CUST-1")
                    .build();

            assertThat(values(graph)).extracting(MetricValue::scopeName)
                    .containsExactly("SUP-1", "DC-1", "DC-2", "CUST-1");
            assertThat(values(graph)).extracting(MetricValue::scopeId)
                    .containsExactly(1L, 2L, 3L, 4L);
        }

        @Test
        @DisplayName("an empty network produces no rows, which is not an error")
        void emptyNetworkProducesNoRows() {
            assertThat(values(GraphFixtures.network().build())).isEmpty();
        }
    }

    @Nested
    @DisplayName("sharing with ROBUSTNESS_TARGETED")
    class SharedDerivation {

        @Test
        @DisplayName("both calculators over one context compute the criticalities once")
        void criticalityIsMemoisedOnTheContext() {
            // The suite's most expensive computation is one maximum flow per node. Two calculators
            // need the same answer, and MetricContext.derived is what stops it being run twice —
            // asserted here as identity, since a second computation would return a new map.
            MetricContext ctx = MetricContext.of(GraphFixtures.verificationNetwork());

            assertThat(ServiceableDemand.criticality(ctx))
                    .isSameAs(ServiceableDemand.criticality(ctx));
        }
    }
}
