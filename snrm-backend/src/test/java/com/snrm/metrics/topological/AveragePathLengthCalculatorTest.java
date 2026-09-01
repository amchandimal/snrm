package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code AVG_PATH}: the mean hop count over the ordered pairs that have a directed path.
 *
 * <p>The two readings the definition takes are what these tests are really about — unreachable pairs
 * are excluded rather than counted, and distance is arcs rather than lead time or capacity.
 */
class AveragePathLengthCalculatorTest {

    private static double averagePath(NetworkGraph graph) {
        return new AveragePathLengthCalculator().compute(MetricContext.of(graph)).get(0).value();
    }

    @Test
    @DisplayName("a 3-node chain: (1 + 2 + 1) / 3")
    void chain() {
        // A→B 1, A→C 2, B→C 1. Three connected pairs of the six ordered ones.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("A").plant("B").customer("C", 10)
                .link("A", "B").link("B", "C")
                .build();

        assertThat(averagePath(graph)).isEqualTo(4.0 / 3, within(1e-12));
    }

    @Test
    @DisplayName("a diamond takes the shorter of two routes: (1+1+2+1+1) / 5")
    void diamondUsesShortestPaths() {
        // A→D is 2 through either arm, and there is no route that makes it 3.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("A").dc("B").dc("C").customer("D", 10)
                .link("A", "B").link("A", "C")
                .link("B", "D").link("C", "D")
                .build();

        assertThat(averagePath(graph)).isEqualTo(6.0 / 5, within(1e-12));
    }

    @Test
    @DisplayName("unreachable pairs are excluded, not counted")
    void excludesUnreachablePairs() {
        // Two disjoint arcs. Four nodes give twelve ordered pairs, of which two are connected and
        // both are one hop, so the answer is 1 — not 1/6 (counting all pairs) and not infinite.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("A").customer("B", 10)
                .supplier("C").customer("D", 10)
                .link("A", "B").link("C", "D")
                .build();

        assertThat(averagePath(graph)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("distance is hops, not lead time")
    void ignoresLeadTime() {
        // The long way round is two arcs of one period each; the direct arc is one arc of ninety.
        // By hops the direct arc wins and every pair is 1 or 2; by lead time it would not.
        NetworkGraph graph = GraphFixtures.network()
                .supplier("A").dc("B").customer("C", 10)
                .link("A", "B", null, 1)
                .link("B", "C", null, 1)
                .link("A", "C", null, 90)
                .build();

        // A→B 1, A→C 1 (the direct arc), B→C 1 → three pairs, three hops.
        assertThat(averagePath(graph)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a network where nothing reaches anything reports 0")
    void noConnectedPairsIsZero() {
        assertThat(averagePath(GraphFixtures.network().build())).isEqualTo(0.0);
        assertThat(averagePath(GraphFixtures.network().supplier("A").dc("B").build()))
                .isEqualTo(0.0);
    }
}
