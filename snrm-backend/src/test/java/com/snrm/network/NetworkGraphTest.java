package com.snrm.network;

import org.jgrapht.Graph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The snapshot the engines run on, and in particular its JGraphT view.
 *
 * <p>The graph is the contract between the network module and everything computational: vertices are
 * node ids, edges are {@link GraphLink} records, and the weight of an arc is what it can carry in a
 * period. An error in any of those three is invisible in this module and surfaces as a resilience
 * number that is quietly wrong, which is why they are pinned here rather than left to the metric
 * tests to notice.
 */
class NetworkGraphTest {

    private static NetworkGraph chain() {
        return GraphFixtures.network()
                .supplier("SUP-1", 500.0)
                .plant("PLANT-1", 400.0)
                .customer("CUST-1", 40)
                .link("SUP-1", "PLANT-1", 300.0)
                .link("PLANT-1", "CUST-1", null)
                .build();
    }

    @Nested
    @DisplayName("the JGraphT view")
    class JGraphTView {

        @Test
        @DisplayName("vertices are node ids and edges are the snapshot's own link records")
        void carriesTheSameStructure() {
            NetworkGraph graph = chain();
            Graph<Long, GraphLink> jgrapht = graph.jgrapht();

            assertThat(jgrapht.vertexSet()).containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(jgrapht.edgeSet()).containsExactlyInAnyOrderElementsOf(graph.links());
        }

        @Test
        @DisplayName("an edge runs from its source node to its target node")
        void keepsArcDirection() {
            NetworkGraph graph = chain();
            Graph<Long, GraphLink> jgrapht = graph.jgrapht();
            GraphLink first = graph.links().get(0);

            assertThat(jgrapht.getEdgeSource(first)).isEqualTo(graph.node(1L).id());
            assertThat(jgrapht.getEdgeTarget(first)).isEqualTo(graph.node(2L).id());
            // Directed, so nothing runs the other way.
            assertThat(jgrapht.getEdge(2L, 1L)).isNull();
        }

        @Test
        @DisplayName("the weight of an arc is its per-period capacity")
        void weightIsCapacity() {
            NetworkGraph graph = chain();
            Graph<Long, GraphLink> jgrapht = graph.jgrapht();

            assertThat(jgrapht.getEdgeWeight(graph.links().get(0))).isEqualTo(300.0);
        }

        @Test
        @DisplayName("an unconstrained arc weighs infinity, not zero")
        void unconstrainedArcIsInfinite() {
            NetworkGraph graph = chain();
            Graph<Long, GraphLink> jgrapht = graph.jgrapht();

            // Zero would be the dangerous alternative: it reads as "this arc can carry nothing",
            // which is the opposite of what a null capacity means. Consumers substitute a finite
            // bound of their own — see ServiceableDemand.
            assertThat(jgrapht.getEdgeWeight(graph.links().get(1)))
                    .isEqualTo(Double.POSITIVE_INFINITY);
        }

        @Test
        @DisplayName("the view is unmodifiable, so a snapshot cannot be edited through it")
        void isUnmodifiable() {
            Graph<Long, GraphLink> jgrapht = chain().jgrapht();

            // The whole point of the snapshot is that it can be published to the parallel
            // replications without a lock. A mutable view would forfeit that.
            assertThatThrownBy(() -> jgrapht.addVertex(99L))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> jgrapht.removeVertex(1L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a network with no links is still a graph with its vertices")
        void toleratesAnEmptyEdgeSet() {
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1")
                    .customer("CUST-1", 10)
                    .build();

            assertThat(graph.jgrapht().vertexSet()).containsExactlyInAnyOrder(1L, 2L);
            assertThat(graph.jgrapht().edgeSet()).isEmpty();
        }
    }

    @Nested
    @DisplayName("adjacency")
    class Adjacency {

        @Test
        @DisplayName("outbound and inbound index the same links from either end")
        void indexesBothDirections() {
            NetworkGraph graph = chain();

            assertThat(graph.outbound(1L)).extracting(GraphLink::targetNodeId).containsExactly(2L);
            assertThat(graph.inbound(3L)).extracting(GraphLink::sourceNodeId).containsExactly(2L);
            assertThat(graph.outbound(3L)).isEmpty();
            assertThat(graph.inbound(1L)).isEmpty();
        }

        @Test
        @DisplayName("the node and link lists are unmodifiable copies")
        void collectionsAreUnmodifiable() {
            NetworkGraph graph = chain();
            List<GraphNode> nodes = graph.nodes();

            assertThatThrownBy(() -> nodes.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
