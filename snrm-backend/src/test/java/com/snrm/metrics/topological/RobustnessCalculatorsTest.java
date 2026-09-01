package com.snrm.metrics.topological;

import com.snrm.metrics.MetricContext;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Rr/Rt pair, and the largest-connected-component curve both are built on. The
 * index is the Schneider/Lou robustness {@code R = Σ S(k) / (n·S(0))}, k = 1 … n — see
 * {@link ComponentCurve} for why the k = 0 term is the normaliser and not a summand.
 *
 * <p>The reference network here is a three-node chain, whose curve is short enough to write out:
 *
 * <pre>
 *   SUP-1 ──▶ PLANT-1 ──▶ CUST-1        undirected:  S — P — C
 * </pre>
 *
 * <p>Its index under every removal order is computable by hand, and its exact random-removal
 * expectation is 8/27 — derived in {@link RandomRemoval#chainExpectation()} below.
 */
class RobustnessCalculatorsTest {

    private static final double TOLERANCE = 1e-12;

    private static NetworkGraph chain() {
        return GraphFixtures.network()
                .supplier("SUP-1").plant("PLANT-1").customer("CUST-1", 10)
                .link("SUP-1", "PLANT-1").link("PLANT-1", "CUST-1")
                .build();
    }

    private static GraphIndex indexOf(NetworkGraph graph) {
        return GraphIndex.of(MetricContext.of(graph));
    }

    @Nested
    @DisplayName("the largest-component curve")
    class Curve {

        @Test
        @DisplayName("removing from an end keeps the rest whole: S = 3, 2, 1, 0")
        void removingEndpointsKeepsItConnected() {
            int[] curve = ComponentCurve.curve(indexOf(chain()), new int[] {0, 1, 2});

            assertThat(curve).containsExactly(3, 2, 1, 0);
            // R = (2 + 1 + 0) / (3 · 3) = 1/3, which is the ceiling of the index for n = 3: a
            // network that loses exactly one node of its largest component per removal scores
            // (n−1)/2n, and a path removed end-first attains it.
            assertThat(ComponentCurve.robustness(curve, 3)).isEqualTo(1.0 / 3, within(TOLERANCE));
        }

        @Test
        @DisplayName("removing the middle splits it in two: S = 3, 1, 1, 0")
        void removingTheHubFragmentsIt() {
            int[] curve = ComponentCurve.curve(indexOf(chain()), new int[] {1, 0, 2});

            assertThat(curve).containsExactly(3, 1, 1, 0);
            assertThat(ComponentCurve.robustness(curve, 3)).isEqualTo(2.0 / 9, within(TOLERANCE));
        }

        @Test
        @DisplayName("components are weak — an arc counts whichever way it points")
        void connectivityIsWeak() {
            // Every arc runs downstream, so no two nodes are strongly connected and a strong
            // reading would report 1 at every step. This network is one weak component of 4.
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("A").dc("B").dc("C").customer("D", 10)
                    .link("A", "B").link("A", "C").link("B", "D").link("C", "D")
                    .build();

            assertThat(ComponentCurve.curve(indexOf(graph), new int[] {0, 1, 2, 3})[0])
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("an empty network has no index")
        void emptyNetwork() {
            assertThat(ComponentCurve.expectedRobustness(indexOf(GraphFixtures.network().build())))
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("ROBUSTNESS_RANDOM")
    class RandomRemoval {

        private double random(NetworkGraph graph) {
            return new RobustnessRandomCalculator().compute(MetricContext.of(graph)).get(0).value();
        }

        @Test
        @DisplayName("the chain's exact expectation is 8/27")
        void chainExpectation() {
            // E[S(0)] = 3 — the normaliser, not a summand.
            // E[S(1)]: remove S → {P,C} joined → 2; remove P → {S,C} apart → 1; remove C → 2.
            //          (2 + 1 + 2)/3 = 5/3.
            // E[S(2)]: one node left in every case → 1.
            // E[S(3)] = 0.
            // Σ E[S(k)], k = 1…3  =  5/3 + 1 + 0  =  8/3.
            // R = (8/3) / (3 · 3) = 8/27.
            assertThat(random(chain())).isEqualTo(8.0 / 27, within(TOLERANCE));
        }

        @Test
        @DisplayName("it is exact below the enumeration limit, so it has no seed to depend on")
        void isDeterministicWhenEnumerated() {
            assertThat(random(chain())).isEqualTo(random(chain()));
        }

        @Test
        @DisplayName("above the enumeration limit it is sampled, and still reproducible")
        void isDeterministicWhenSampled() {
            // Reproducibility is a research-validity requirement: a structural metric
            // that answered differently on each request would make a variant comparison partly a
            // comparison of seeds. Twenty nodes is past the exact branch, so this exercises the
            // sampled one.
            NetworkGraph long20 = path(20);
            assertThat(long20.nodes()).hasSizeGreaterThan(ComponentCurve.EXACT_ENUMERATION_LIMIT);

            double first = random(long20);
            double second = random(long20);

            assertThat(first).isEqualTo(second);
            // A path is the least robust connected shape there is, but it is connected, so the
            // index sits strictly inside the range rather than at either end.
            assertThat(first).isStrictlyBetween(0.0, 0.5);
        }

        @Test
        @DisplayName("a single node sits at the boundary of the definition")
        void singleNode() {
            // S = 1, 0: the one removal step leaves nothing, so the sum over k = 1 … n is empty of
            // survivors and R = 0. The intact S(0) = 1 is only the normaliser — under the previous
            // trapezoidal reading this same network scored ½, which is recorded here so a future
            // change to the formula is a deliberate one.
            assertThat(random(GraphFixtures.network().supplier("SUP-1").build())).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("ROBUSTNESS_TARGETED")
    class TargetedRemoval {

        private double targeted(NetworkGraph graph) {
            return new RobustnessTargetedCalculator().compute(MetricContext.of(graph)).get(0).value();
        }

        @Test
        @DisplayName("the chain is removed customer-first, which keeps it whole to the end")
        void chainIsRemovedInNameOrder() {
            // Every node of the chain has criticality 1, so the tie-break decides the order and it
            // is node name ascending: CUST-1, PLANT-1, SUP-1. That is removal from one end, so the
            // curve is 3, 2, 1, 0 and R = (2 + 1 + 0)/9 = 1/3 — the ceiling for n = 3.
            assertThat(targeted(chain())).isEqualTo(1.0 / 3, within(TOLERANCE));
        }

        @Test
        @DisplayName("on the chain, targeted removal scores above random — and that is the finding")
        void targetedCanExceedRandom() {
            // The same divergence the six-node verification network shows (§7.3 of
            // docs/metric-verification.md), in three nodes: criticality ranks by flow and puts a
            // leaf first (here the customer, by the name tie-break), so an attack that starts
            // there is gentler on the structure than chance is — 1/3 against 8/27. Flow fragility
            // and structural fragility are different lenses, which is why both are reported.
            assertThat(targeted(chain()))
                    .isGreaterThan(new RobustnessRandomCalculator()
                            .compute(MetricContext.of(chain())).get(0).value());
        }

        @Test
        @DisplayName("a hub with the highest criticality is removed first and the network falls apart")
        void attacksTheHubWhenCriticalityRanksIt() {
            // SUP-1 → HUB → {CUST-1, CUST-2}. Removing HUB strands both customers, so its
            // criticality is 1 alongside SUP-1's — and HUB precedes SUP-1 by name, so the order is
            // HUB, SUP-1, CUST-1, CUST-2.
            //   k=0: whole, S=4.   k=1: HUB gone, {SUP-1},{C1},{C2}, S=1.
            //   k=2: S=1.   k=3: S=1.   k=4: S=0.
            //   R = (1 + 1 + 1 + 0) / 16 = 3/16
            NetworkGraph graph = GraphFixtures.network()
                    .supplier("SUP-1").dc("HUB").customer("CUST-1", 5).customer("CUST-2", 5)
                    .link("SUP-1", "HUB").link("HUB", "CUST-1").link("HUB", "CUST-2")
                    .build();

            assertThat(targeted(graph)).isEqualTo(3.0 / 16, within(TOLERANCE));
        }

        @Test
        @DisplayName("an empty network has no index")
        void emptyNetwork() {
            assertThat(targeted(GraphFixtures.network().build())).isEqualTo(0.0);
        }
    }

    /** A directed path of {@code n} nodes: supplier, DCs, customer. */
    private static NetworkGraph path(int n) {
        GraphFixtures.Builder builder = GraphFixtures.network().supplier("N0");
        for (int i = 1; i < n - 1; i++) {
            builder.dc("N" + i);
        }
        builder.customer("N" + (n - 1), 10);
        for (int i = 0; i < n - 1; i++) {
            builder.link("N" + i, "N" + (i + 1));
        }
        return builder.build();
    }
}
