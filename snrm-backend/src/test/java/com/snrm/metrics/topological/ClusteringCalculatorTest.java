package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code CLUSTERING}: average local clustering, on the undirected form of the network.
 *
 * <p>The departure from "on the directed network" is the thing worth pinning here, and
 * {@link #countsAnAcyclicTriangle()} is the test that would fail under a directed reading — which is
 * the reading that reports 0 for every echelon-respecting supply network and therefore measures
 * nothing.
 */
class ClusteringCalculatorTest {

    private static double clustering(NetworkGraph graph) {
        return new ClusteringCalculator().compute(MetricContext.of(graph)).get(0).value();
    }

    @Test
    @DisplayName("a triangle is 1: every node's two neighbours are joined")
    void triangle() {
        NetworkGraph graph = GraphFixtures.network()
                .dc("A").dc("B").dc("C")
                .link("A", "B").link("B", "C").link("C", "A")
                .build();

        assertThat(clustering(graph)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an acyclic triangle counts too — direction is dropped for this metric")
    void countsAnAcyclicTriangle() {
        // A→B, A→C, B→C: no cycle, so no directed triangle, so a directed clustering coefficient
        // would be 0 here and 0 on every layered supply network. Undirected it is a triangle, and
        // it is a real alternative route: A can reach C directly or through B.
        NetworkGraph graph = GraphFixtures.network()
                .plant("A").dc("B").dc("C")
                .link("A", "B").link("A", "C").link("B", "C")
                .build();

        assertThat(clustering(graph)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a chain has no triangle at all")
    void chainIsZero() {
        NetworkGraph graph = GraphFixtures.network()
                .supplier("A").plant("B").customer("C", 10)
                .link("A", "B").link("B", "C")
                .build();

        assertThat(clustering(graph)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a star is 0: the hub's neighbours never touch each other")
    void starIsZero() {
        NetworkGraph graph = GraphFixtures.network()
                .plant("HUB").customer("A", 1).customer("B", 1).customer("C", 1)
                .link("HUB", "A").link("HUB", "B").link("HUB", "C")
                .build();

        assertThat(clustering(graph)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("nodes with fewer than two neighbours contribute 0 and still count in the average")
    void leavesDivideTheAverage() {
        // A triangle B–C–D with a leaf A hanging off B. B now has three neighbours (A, C, D) of
        // which one pair is joined, so C(B) = 1/3; C and D remain 1; A is a leaf and contributes 0.
        // Average = (0 + 1/3 + 1 + 1) / 4 = 7/12.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("A").dc("B").dc("C").dc("D")
                .link("A", "B")
                .link("B", "C").link("C", "D").link("D", "B")
                .build();

        assertThat(clustering(graph)).isEqualTo(7.0 / 12, within(1e-12));
    }

    @Test
    @DisplayName("a pair joined in both directions is one neighbour, not two")
    void bidirectionalPairIsOneNeighbour() {
        // A→B and B→A. Each node has a single distinct neighbour, so neither has a pair of
        // neighbours to be joined and both contribute 0. Counting the arc twice would give each a
        // degree of 2 and invent a pair that does not exist.
        NetworkGraph graph = GraphFixtures.network()
                .dc("A").dc("B")
                .link("A", "B").link("B", "A")
                .build();

        assertThat(clustering(graph)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("an empty network reports 0 rather than dividing by zero")
    void emptyNetworkIsZero() {
        assertThat(clustering(GraphFixtures.network().build())).isEqualTo(0.0);
    }
}
