package com.snrm.simulation;

import com.snrm.common.ProgressSink;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import com.snrm.scenario.DisruptionTargetType;
import com.snrm.scenario.PlannedEvent;
import com.snrm.scenario.ScenarioPlan;
import com.snrm.scenario.StepRecoveryProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Asserts every number in {@code docs/simulation-verification.md} §7.2, value by value, plus the two
 * conventions that document says it cannot provoke (§9) — the baseline-run copy-in and the
 * unavailable path.
 *
 * <p><strong>That document is the specification, and this test transcribes it</strong>, exactly as
 * {@link SimulationVerificationTest} transcribes §4–§8. Each assertion names the section that derives
 * its figure. When the model changes, change the document first: re-deriving these numbers from the
 * new code would turn the test into a record of what the code does, which is the one thing it must
 * not be.
 *
 * <p>Runs with no Spring context, no database and no Docker. The per-element series is a value
 * object all the way from the loop to {@code SimulationRunWriter}, so it can be checked
 * without JPA at all — which is what {@code GraphFixtures} exists for.
 */
@DisplayName("docs/simulation-verification.md §7.2 — the per-element series (FR-18)")
class ElementTimeseriesTest {

    /** §8: the tolerance the document states, and for the same reason. */
    private static final double TOLERANCE = 1e-9;

    /** Snapshot order is declaration order (GraphFixtures): SUP-1, PLANT-1, CUST-1. */
    private static final int SUP = 0;
    private static final int PLANT = 1;
    private static final int CUST = 2;

    /** Links likewise: a = SUP-1 → PLANT-1, b = PLANT-1 → CUST-1. */
    private static final int SUP_TO_PLANT = 0;
    private static final int PLANT_TO_CUST = 1;

    private static SimulationTraces traces;
    private static ElementSeries elements;

    @BeforeAll
    static void runTheVerificationScenario() {
        NetworkGraph graph = GraphFixtures.simulationChain();
        // §1.4 and §1.5, identical to SimulationVerificationTest: the two documents describe one run.
        ScenarioPlan plan = new ScenarioPlan(1L, "Plant outage", graph.networkId(),
                List.of(new PlannedEvent(1L, "NODE 2 (PLANT-1)", DisruptionTargetType.NODE,
                        Set.of(2L), Set.of(), 3, 3, 0.5, new StepRecoveryProfile(), 1.0)),
                List.of());
        traces = new MonteCarloRunner().run(graph, plan, params(1, true), ProgressSink.none());
        elements = traces.elements();
    }

    private static SimulationParams params(int replications, boolean record) {
        return new SimulationParams(replications, 20260802L, 10, 0, 0, true, false, 0, null, 1000,
                record, SimulationParams.ENGINE_VERSION);
    }

    @Nested
    @DisplayName("§7.2.1 — the pipeline is empty on this network")
    class EmptyPipeline {

        /** Both lead times are 0 and no node dwells, so nothing is ever in flight. */
        @Test
        @DisplayName("arrivals and inTransit are 0 at every node in every period")
        void nothingIsEverInFlight() {
            for (ElementSeries.NodeSeries node : elements.nodes()) {
                assertThat(node.arrivals()).as("node %d arrivals", node.nodeId())
                        .containsOnly(0.0);
                assertThat(node.inTransit()).as("node %d inTransit", node.nodeId())
                        .containsOnly(0.0);
            }
        }

        /**
         * §7.1: the network totals {@code V9__element_timeseries.sql} adds to {@code run_timeseries},
         * read off the traces the writer aggregates. {@code inPipeline} is the sum of the
         * {@code inTransit} column above, so it is 0 throughout; {@code endingInventory} is
         * PLANT-1's stock and nothing else.
         */
        @Test
        @DisplayName("the run_timeseries totals are endingInventory 50, 0… and inPipeline 0")
        void theNetworkTotalsAgreeWithTheElements() {
            ReplicationTrace run = traces.disrupted().get(0);
            assertThat(run.period(0).endingInventory()).isCloseTo(50, within(TOLERANCE));
            for (int t = 1; t < 10; t++) {
                assertThat(run.period(t).endingInventory()).as("period %d", t)
                        .isCloseTo(0, within(TOLERANCE));
            }
            for (int t = 0; t < 10; t++) {
                assertThat(run.period(t).inPipeline()).as("period %d", t)
                        .isCloseTo(0, within(TOLERANCE));
            }
        }
    }

    @Nested
    @DisplayName("§7.2.2 — throughput is the flow across a node's own capacity arc")
    class Throughput {

        /**
         * §7.2.2: stock enters the flow at {@code out(i)} and never crosses the capacity arc, so
         * PLANT-1's throughput is 0 in the two periods it serves out of its opening 100.
         */
        @Test
        @DisplayName("PLANT-1 moves 50 a period out of stock and has 0 throughput doing it")
        void stockBypassesTheCapacityArc() {
            assertThat(node(PLANT).throughput())
                    .containsExactly(new double[] {0, 0, 50, 40, 40, 40, 50, 50, 50, 50},
                            within(TOLERANCE));
        }

        /** §7.2.2: SUP-1 produces only from period 2, and only 40 while the plant is halved. */
        @Test
        @DisplayName("SUP-1's throughput is 0, 0, 50, 40, 40, 40, then 50")
        void productionStartsWhenTheStockRunsOut() {
            assertThat(node(SUP).throughput())
                    .containsExactly(new double[] {0, 0, 50, 40, 40, 40, 50, 50, 50, 50},
                            within(TOLERANCE));
        }

        /**
         * §7.2.2: a lead-0 arc lands at {@code in(target)}, so material passing through the customer
         * does cross its capacity arc — which is invisible in the cost column only because
         * {@code CUST-1}'s var cost is 0.
         */
        @Test
        @DisplayName("CUST-1 has throughput even though it produces nothing")
        void passThroughCrossesTheCapacityArc() {
            assertThat(node(CUST).throughput())
                    .containsExactly(new double[] {50, 50, 50, 40, 40, 40, 50, 50, 50, 50},
                            within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("§7.2.3 — the three nodes in full")
    class Nodes {

        /** §7.2.3, and §4.3's stock column: 100 drains over two periods and nothing is held again. */
        @Test
        void onHand() {
            assertThat(node(SUP).onHand()).containsOnly(0.0);
            assertThat(node(PLANT).onHand())
                    .containsExactly(new double[] {50, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                            within(TOLERANCE));
            assertThat(node(CUST).onHand()).containsOnly(0.0);
        }

        /** §7.2.3: demand sits on CUSTOMER nodes alone, so the other two rows are 0. */
        @Test
        void servedAndUnservedSitOnTheCustomerAlone() {
            assertThat(node(CUST).served())
                    .containsExactly(new double[] {50, 50, 50, 40, 40, 40, 50, 50, 50, 50},
                            within(TOLERANCE));
            assertThat(node(CUST).unserved())
                    .containsExactly(new double[] {0, 0, 0, 10, 10, 10, 0, 0, 0, 0},
                            within(TOLERANCE));
            for (int i : new int[] {SUP, PLANT}) {
                assertThat(node(i).served()).containsOnly(0.0);
                assertThat(node(i).unserved()).containsOnly(0.0);
            }
        }

        /** §4.1's availability row, read at the element rather than at the event. */
        @Test
        void availability() {
            assertThat(node(PLANT).availability())
                    .containsExactly(new double[] {1, 1, 1, 0.5, 0.5, 0.5, 1, 1, 1, 1},
                            within(TOLERANCE));
            assertThat(node(SUP).availability()).containsOnly(1.0);
            assertThat(node(CUST).availability()).containsOnly(1.0);
        }

        /**
         * §7.2.3, the reading this whole column exists to make possible: <strong>null and 0.0 are
         * different answers</strong>. Null says nothing was dispatched toward the node; 0.0 says
         * what was dispatched travelled a lead of zero and arrived the same period.
         */
        @Test
        @DisplayName("inboundLead: null where nothing was dispatched, 0.0 where a lead-0 arc carried")
        void inboundLead() {
            // A supply origin has no inbound arc at all, so this is structural rather than
            // circumstantial: it can never be anything but absent.
            for (int t = 0; t < 10; t++) {
                assertAbsent(node(SUP).inboundLead(), t, "SUP-1 has no inbound arc");
            }
            // PLANT-1's inbound arc carries nothing while the plant serves out of its own stock.
            assertAbsent(node(PLANT).inboundLead(), 0, "arc a is idle while stock lasts");
            assertAbsent(node(PLANT).inboundLead(), 1, "arc a is idle while stock lasts");
            for (int t = 2; t < 10; t++) {
                assertDefined(node(PLANT).inboundLead(), t, 0.0);
            }
            // Arc b carries in every period, at a lead of 0.
            for (int t = 0; t < 10; t++) {
                assertDefined(node(CUST).inboundLead(), t, 0.0);
            }
        }

        /**
         * §7.2.3: the baseline set drains the same stock over the same two periods, so the two
         * inventory curves coincide — an element-level triangle of zero area beside a service-level
         * one that is not.
         */
        @Test
        @DisplayName("the baseline columns carry the paired set, and here it matches")
        void baselineColumns() {
            assertThat(node(PLANT).baselineOnHand())
                    .containsExactly(new double[] {50, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                            within(TOLERANCE));
            assertThat(node(CUST).baselineServed()).containsOnly(50.0);
            assertThat(node(SUP).baselineServed()).containsOnly(0.0);
        }
    }

    @Nested
    @DisplayName("§7.2.4 — the two links in full")
    class Links {

        @Test
        void flow() {
            assertThat(link(SUP_TO_PLANT).flow())
                    .containsExactly(new double[] {0, 0, 50, 40, 40, 40, 50, 50, 50, 50},
                            within(TOLERANCE));
            assertThat(link(PLANT_TO_CUST).flow())
                    .containsExactly(new double[] {50, 50, 50, 40, 40, 40, 50, 50, 50, 50},
                            within(TOLERANCE));
        }

        /**
         * §7.2.4: <strong>an idle arc at full availability reports 0.0, not null.</strong> Null
         * would say the arc could not be measured; 0.0 says it was measured and carried nothing.
         */
        @Test
        void utilisationIsFlowOverAHundred() {
            double[] a = link(SUP_TO_PLANT).utilisation();
            assertThat(a).containsExactly(
                    new double[] {0, 0, 0.5, 0.4, 0.4, 0.4, 0.5, 0.5, 0.5, 0.5}, within(TOLERANCE));
            assertDefined(a, 0, 0.0);
            assertThat(link(PLANT_TO_CUST).utilisation()).containsExactly(
                    new double[] {0.5, 0.5, 0.5, 0.4, 0.4, 0.4, 0.5, 0.5, 0.5, 0.5},
                    within(TOLERANCE));
        }

        /** §7.2.4: the event targets a node, so neither arc's availability ever moves. */
        @Test
        void availability() {
            assertThat(link(SUP_TO_PLANT).availability()).containsOnly(1.0);
            assertThat(link(PLANT_TO_CUST).availability()).containsOnly(1.0);
        }

        /** §7.2.4: the two flow curves differ only in periods 3–5, by exactly 10 a period. */
        @Test
        void baselineFlow() {
            assertThat(link(SUP_TO_PLANT).baselineFlow())
                    .containsExactly(new double[] {0, 0, 50, 50, 50, 50, 50, 50, 50, 50},
                            within(TOLERANCE));
            assertThat(link(PLANT_TO_CUST).baselineFlow()).containsOnly(50.0);
        }
    }

    @Nested
    @DisplayName("The two nulls of utilisation — §9, provoked on their own micro-networks")
    class UndefinedUtilisation {

        /**
         * "An unconstrained element cannot be partly disrupted" ({@code FlowAllocator.adjusted}): a
         * link with no declared capacity has no fraction to be at, so its utilisation is absent in
         * every period. Reporting 0 would say it was idle while it was in fact carrying 50 a period.
         */
        @Test
        @DisplayName("an uncapped arc has no utilisation, in every period, however much it carries")
        void uncappedArc() {
            NetworkGraph graph = GraphFixtures.network()
                    .horizon(4)
                    .supplier("SUP-1", 100.0)
                    .customer("CUST-1", 50).unitValue(20)
                    .link("SUP-1", "CUST-1", null, 0)
                    .build();

            ElementSeries series = new MonteCarloRunner()
                    .run(graph, ScenarioPlan.baseline(graph.networkId()),
                            new SimulationParams(1, 7L, 4, 0, 0, false, false, 0, null, 1000, true,
                                    SimulationParams.ENGINE_VERSION),
                            ProgressSink.none())
                    .elements();

            ElementSeries.LinkSeries arc = series.links().get(0);
            assertThat(arc.flow()).containsOnly(50.0);
            for (int t = 0; t < 4; t++) {
                assertAbsent(arc.utilisation(), t, "the arc declares no capacity");
            }
        }

        /**
         * An arc an event has taken to zero available capacity is <strong>dark, not idle</strong>:
         * {@code 0/0} is not 0, and a view that drew it as 0% utilised would say the network chose
         * not to use a working link. {@code availability} is the column that carries the outage.
         */
        @Test
        @DisplayName("an arc taken to zero capacity has no utilisation, and availability says why")
        void arcTakenToZero() {
            NetworkGraph graph = GraphFixtures.network()
                    .horizon(4)
                    .supplier("SUP-1", 100.0)
                    .customer("CUST-1", 50).unitValue(20)
                    .link("SUP-1", "CUST-1", 100.0, 0)
                    .build();
            // Severity 1.0 over periods 1 and 2 — the only outage a capacity of 100 against a flow
            // of 50 can feel, for the reason samples/four-echelon-playback/README.md §8.1 states.
            ScenarioPlan plan = new ScenarioPlan(1L, "Link cut", graph.networkId(),
                    List.of(new PlannedEvent(1L, "LINK 1", DisruptionTargetType.LINK,
                            Set.of(), Set.of(1L), 1, 2, 1.0, new StepRecoveryProfile(), 1.0)),
                    List.of());

            ElementSeries series = new MonteCarloRunner()
                    .run(graph, plan,
                            new SimulationParams(1, 7L, 4, 0, 0, false, false, 0, null, 1000, true,
                                    SimulationParams.ENGINE_VERSION),
                            ProgressSink.none())
                    .elements();

            ElementSeries.LinkSeries arc = series.links().get(0);
            assertThat(arc.availability())
                    .containsExactly(new double[] {1, 0, 0, 1}, within(TOLERANCE));
            assertDefined(arc.utilisation(), 0, 0.5);
            assertAbsent(arc.utilisation(), 1, "the outage left no capacity to be a fraction of");
            assertAbsent(arc.utilisation(), 2, "the outage left no capacity to be a fraction of");
            assertDefined(arc.utilisation(), 3, 0.5);
            // And the flow really is 0 there — absent utilisation is not hiding a served period.
            assertThat(arc.flow()).containsExactly(new double[] {50, 0, 0, 50}, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("The baseline-run copy-in convention — V9__element_timeseries.sql, FR-17")
    class BaselineRunCopyIn {

        /**
         * A run with no scenario skips the paired set ({@code runsPairedBaseline}), so
         * there is no undisrupted series to mirror and the three baseline columns take the run's own.
         * <strong>Equality is the assertion, not absence:</strong> the columns are populated, and the
         * per-element resilience triangle has zero area because the run had nothing to recover from.
         * This is the same convention {@code SimulationRunWriter.aggregate} takes for
         * {@code baseline_served_demand}; taking a different one here would make the network curve
         * and the element curves disagree about one run.
         */
        @Test
        @DisplayName("a baseline run writes its own series into the baseline columns, value for value")
        void baselineColumnsAreTheRunsOwn() {
            NetworkGraph graph = GraphFixtures.simulationChain();
            SimulationTraces traces = new MonteCarloRunner()
                    .run(graph, ScenarioPlan.baseline(graph.networkId()), params(1, true),
                            ProgressSink.none());

            assertThat(traces.baseline()).as("the pairing is skipped (FR-17)").isEmpty();
            ElementSeries series = traces.elements();
            for (ElementSeries.NodeSeries node : series.nodes()) {
                assertThat(node.baselineOnHand()).as("node %d", node.nodeId())
                        .containsExactly(node.onHand(), within(TOLERANCE));
                assertThat(node.baselineServed()).as("node %d", node.nodeId())
                        .containsExactly(node.served(), within(TOLERANCE));
            }
            for (ElementSeries.LinkSeries link : series.links()) {
                assertThat(link.baselineFlow()).as("link %d", link.linkId())
                        .containsExactly(link.flow(), within(TOLERANCE));
            }
        }

        /**
         * The other half of the convention: with a scenario the columns are the <em>paired</em>
         * set's, and on this network they differ from the disrupted ones in periods 3–5. If this
         * ever passes as equality, the baseline replications are being folded into the wrong sums —
         * which would make every element-level triangle empty and nothing else fail.
         */
        @Test
        @DisplayName("with a scenario the baseline columns are the paired set's, and they differ")
        void aPairedRunDoesNotCopyIn() {
            // Period 3 is the first disrupted one: the plant is halved to 40 while the undisrupted
            // twin still moves 50 (§4.3, §4.4).
            assertThat(link(SUP_TO_PLANT).flow()[3]).isCloseTo(40, within(TOLERANCE));
            assertThat(link(SUP_TO_PLANT).baselineFlow()[3]).isCloseTo(50, within(TOLERANCE));
            assertThat(node(CUST).served()[3]).isCloseTo(40, within(TOLERANCE));
            assertThat(node(CUST).baselineServed()[3]).isCloseTo(50, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("The unavailable path — a run that recorded nothing")
    class Unavailable {

        /**
         * {@code recordElementTimeseries: false} produces no series at all, which is what
         * {@code SimulationRunWriter} reads as "write no element rows" and what the API then reports
         * as {@code available: false}. Null rather than an empty {@link ElementSeries}, deliberately:
         * an empty series would be indistinguishable from a network in which nothing ever moved.
         */
        @Test
        @DisplayName("recording off leaves no series behind")
        void recordingOffProducesNoSeries() {
            NetworkGraph graph = GraphFixtures.simulationChain();
            SimulationTraces traces = new MonteCarloRunner()
                    .run(graph, ScenarioPlan.baseline(graph.networkId()), params(1, false),
                            ProgressSink.none());

            assertThat(traces.elements()).isNull();
            // The metric side is untouched: recording changes no simulated number, which is why
            // ENGINE_VERSION does not move for it (§8.1).
            assertThat(traces.disrupted().get(0).totalCost()).isEqualTo(
                    new MonteCarloRunner()
                            .run(graph, ScenarioPlan.baseline(graph.networkId()), params(1, true),
                                    ProgressSink.none())
                            .disrupted().get(0).totalCost());
        }

        /**
         * The shape the API answers with for a run that is not yet {@code DONE}, one that predates
         * {@code V9__element_timeseries.sql}, and one that ran with recording off. Empty lists and
         * a false flag — never a 404 and never a 500, because a run with no element series answered
         * a different question rather than failing.
         */
        @Test
        @DisplayName("the unavailable response is a false flag and two empty lists, never an error")
        void unavailableDto() {
            ElementTimeseriesDto dto = ElementTimeseriesDto.unavailable();

            assertThat(dto.available()).isFalse();
            assertThat(dto.nodes()).isEmpty();
            assertThat(dto.links()).isEmpty();
        }
    }

    /**
     * The flag defaults to true, and — the point of the boxed type — a {@code params_json} written
     * before the flag existed carries no such field and must read back as true rather than as a run
     * that declined to record.
     */
    @Test
    @DisplayName("recordElementTimeseries defaults to true, including for a pre-V9 parameter set")
    void theFlagDefaultsToTrue() {
        assertThat(SimulationParams.resolve(null, 1, 7L, 10, 1L).recordElementTimeseries())
                .isTrue();
        assertThat(SimulationParams.resolve(SimulationParamsDto.empty(), 1, 7L, 10, 1L)
                .recordElementTimeseries()).isTrue();
        assertThat(new SimulationParams(1, 7L, 10, 0, 0, true, false, 0, null, 1000, null,
                SimulationParams.ENGINE_VERSION).recordElementTimeseries())
                .as("a params_json written before V9 has no such field")
                .isTrue();
    }

    private static ElementSeries.NodeSeries node(int index) {
        return elements.nodes().get(index);
    }

    private static ElementSeries.LinkSeries link(int index) {
        return elements.links().get(index);
    }

    /** An entry the run could not define — a SQL NULL and a JSON null downstream, never a 0. */
    private static void assertAbsent(double[] series, int period, String why) {
        assertThat(Double.isNaN(series[period]))
                .as("period %d must be absent: %s (was %s)", period, why, series[period])
                .isTrue();
    }

    private static void assertDefined(double[] series, int period, double expected) {
        assertThat(Double.isNaN(series[period]))
                .as("period %d must be defined, was absent", period)
                .isFalse();
        assertThat(series[period]).as("period %d", period).isCloseTo(expected, within(TOLERANCE));
    }
}
