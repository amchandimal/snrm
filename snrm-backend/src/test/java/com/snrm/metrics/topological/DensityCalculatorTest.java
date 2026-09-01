package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricScope;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code DENSITY} on micro-networks whose answer can be read off the definition.
 *
 * <pre>
 *   DENSITY = m / ( n · (n − 1) )
 * </pre>
 */
class DensityCalculatorTest {

    private static double density(NetworkGraph graph) {
        return new DensityCalculator().compute(MetricContext.of(graph)).get(0).value();
    }

    @Test
    @DisplayName("a 3-node chain has 2 of the 6 possible arcs")
    void chain() {
        NetworkGraph graph = GraphFixtures.network()
                .supplier("SUP-1").plant("PLANT-1").customer("CUST-1", 10)
                .link("SUP-1", "PLANT-1")
                .link("PLANT-1", "CUST-1")
                .build();

        assertThat(density(graph)).isEqualTo(2.0 / 6, within(1e-12));
    }

    @Test
    @DisplayName("the denominator counts ordered pairs, so a fully connected digraph is exactly 1")
    void completeDigraphIsOne() {
        // Six arcs among three nodes — every ordered pair. If the denominator were the undirected
        // n(n−1)/2 this would report 2, which is not a density.
        NetworkGraph graph = GraphFixtures.network()
                .dc("A").dc("B").dc("C")
                .link("A", "B").link("B", "A")
                .link("A", "C").link("C", "A")
                .link("B", "C").link("C", "B")
                .build();

        assertThat(density(graph)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one arc between three nodes is 1/6, not 1/3")
    void directionHalvesTheDensity() {
        NetworkGraph graph = GraphFixtures.network()
                .dc("A").dc("B").dc("C")
                .link("A", "B")
                .build();

        assertThat(density(graph)).isEqualTo(1.0 / 6, within(1e-12));
    }

    @Test
    @DisplayName("a network with fewer than two nodes has no pair that could be connected")
    void degenerateNetworksAreZeroRatherThanUndefined() {
        // 0/0 would be NaN, and the editor recomputes this suite on every edit — including the
        // edit that creates the first node. A hole in the suite there would be worse than a zero.
        assertThat(density(GraphFixtures.network().build())).isEqualTo(0.0);
        assertThat(density(GraphFixtures.network().supplier("SUP-1").build())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("one network-scoped value, and nothing else")
    void producesOneNetworkScopedValue() {
        var values = new DensityCalculator().compute(
                MetricContext.of(GraphFixtures.verificationNetwork()));

        assertThat(values).hasSize(1);
        assertThat(values.get(0).code()).isEqualTo("DENSITY");
        assertThat(values.get(0).scope()).isEqualTo(MetricScope.NETWORK);
        assertThat(values.get(0).scopeId()).isNull();
    }
}
