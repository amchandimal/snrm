package com.snrm.simulation;

import com.snrm.common.ProgressSink;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import com.snrm.scenario.ScenarioPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Lead times, processing dwell and the pipeline — the machinery
 * {@code docs/simulation-verification.md} deliberately keeps out of its way.
 *
 * <blockquote>"Inventory at a node acts as an additional supply source in the period after it is
 * stocked; lead times delay flow arrival by the link's lead-time in periods (modelled as pipeline
 * inventory)."</blockquote>
 *
 * <p>Everything here is deterministic: one replication, no noise, no jitter, no failures, and a
 * scenario with no events. What is being checked is the arithmetic of <em>time</em>, and a stochastic
 * element would only obscure it.
 */
@DisplayName("Pipeline inventory")
class InventoryPipelineTest {

    private static final double TOLERANCE = 1e-6;

    private static ReplicationTrace run(NetworkGraph graph, int horizon, double safetyStockPriority) {
        SimulationParams params = new SimulationParams(1, 7L, horizon, 0, 0, false, false,
                safetyStockPriority, null, 1000, true, SimulationParams.ENGINE_VERSION);
        ScenarioPlan undisrupted = new ScenarioPlan(1L, "none", graph.networkId(), List.of(), List.of());
        SimulationTraces traces = new MonteCarloRunner()
                .run(graph, undisrupted, params, ProgressSink.none());
        return traces.disrupted().get(0);
    }

    /** SUP-1 is given ample capacity so that no test below turns into a tie-break between needs. */
    private static NetworkGraph twoPeriodLink() {
        return GraphFixtures.network()
                .horizon(6)
                .supplier("SUP-1", 500.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "CUST-1", 500.0, 2)
                .build();
    }

    @Test
    @DisplayName("a two-period lead time serves nothing until material has travelled")
    void leadTimeDelaysArrival() {
        ReplicationTrace run = run(twoPeriodLink(), 6, 0.1);

        assertThat(run.period(0).servedDemand()).as("nothing has arrived yet")
                .isCloseTo(0, within(TOLERANCE));
        assertThat(run.period(1).servedDemand()).as("still in transit")
                .isCloseTo(0, within(TOLERANCE));
        assertThat(run.period(2).servedDemand()).as("the first shipment lands")
                .isCloseTo(50, within(TOLERANCE));
    }

    @Test
    @DisplayName("material in transit is counted as in-pipeline until it lands")
    void inTransitIsTracked() {
        ReplicationTrace run = run(twoPeriodLink(), 6, 0.1);

        // The customer's order-up-to level covers its two-period replenishment delay: 50 × (1 + 2).
        assertThat(run.period(0).inPipeline()).as("150 dispatched in period 0, arriving in period 2")
                .isCloseTo(150, within(TOLERANCE));
        // Netting the order against what is already in transit is what stops it being placed again
        // every period while the first shipment is still travelling.
        assertThat(run.period(1).inPipeline()).as("no second order while the first is in flight")
                .isCloseTo(150, within(TOLERANCE));
        assertThat(run.period(2).inPipeline()).as("it has landed")
                .isCloseTo(0, within(TOLERANCE));
    }

    @Test
    @DisplayName("with zero lead times the same chain serves from period 0")
    void zeroLeadTimeIsImmediate() {
        NetworkGraph graph = GraphFixtures.network()
                .horizon(6)
                .supplier("SUP-1", 100.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "CUST-1", 100.0, 0)
                .build();

        assertThat(run(graph, 6, 0).period(0).servedDemand()).isCloseTo(50, within(TOLERANCE));
    }

    @Test
    @DisplayName("processing time is a dwell: material entering a node leaves it that many periods "
            + "later")
    void processingTimeDelaysThroughput() {
        // PLANT-1 takes one period to process. It cannot pass material through in the period it
        // receives it, so the customer is served from period 1 rather than period 0.
        NetworkGraph graph = GraphFixtures.network()
                .horizon(6)
                .supplier("SUP-1", 500.0)
                .plant("PLANT-1", 100.0).processing(1)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "PLANT-1", 500.0, 0)
                .link("PLANT-1", "CUST-1", 500.0, 0)
                .build();

        ReplicationTrace run = run(graph, 6, 0.1);
        assertThat(run.period(0).servedDemand()).as("still inside PLANT-1's dwell")
                .isCloseTo(0, within(TOLERANCE));
        assertThat(run.period(1).servedDemand()).as("dwell complete, and it can ship")
                .isCloseTo(50, within(TOLERANCE));
    }

    @Test
    @DisplayName("opening inventory is available in period 0, not the period after")
    void openingInventoryIsAvailableImmediately() {
        NetworkGraph graph = GraphFixtures.network()
                .horizon(3)
                .supplier("SUP-1", 0.0)                        // can produce nothing at all
                .customer("CUST-1", 50).unitValue(20).stock(50, 0, 0)
                .link("SUP-1", "CUST-1", 100.0, 0)
                .build();

        ReplicationTrace run = run(graph, 3, 0);
        assertThat(run.period(0).servedDemand()).isCloseTo(50, within(TOLERANCE));
        assertThat(run.period(1).servedDemand()).as("and then it is gone")
                .isCloseTo(0, within(TOLERANCE));
    }

    @Test
    @DisplayName("a network with lead times but no order-up-to pull moves nothing — the reason "
            + "replenishment targets one period of covered demand")
    void withoutReplenishmentNothingCrossesALeadTimeArc() {
        NetworkGraph graph = GraphFixtures.network()
                .horizon(6)
                .supplier("SUP-1", 100.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "CUST-1", 100.0, 2)
                .build();

        // safetyStockPriority = 0 switches replenishment off entirely. A unit crossing a lead-time
        // arc cannot serve this period's demand, so with nothing pulling it, nothing moves. This is
        // correct base-stock behaviour and is exactly why the order-up-to level of
        // SimulationNetwork.computeReplenishTargets includes a period of covered demand.
        ReplicationTrace run = run(graph, 6, 0);
        assertThat(run.fillRate()).isCloseTo(0, within(TOLERANCE));
    }

    @Test
    @DisplayName("the pipeline reaches a steady state and stays there")
    void steadyState() {
        NetworkGraph graph = GraphFixtures.network()
                .horizon(12)
                .supplier("SUP-1", 1000.0)
                .dc("DC-1", 1000.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "DC-1", 1000.0, 1)
                .link("DC-1", "CUST-1", 1000.0, 1)
                .build();

        ReplicationTrace run = run(graph, 12, 0.1);
        // Two lead-time arcs, so the network is warming up for the first few periods; by the second
        // half it should be serving in full and staying there.
        for (int t = 6; t < 12; t++) {
            assertThat(run.period(t).servedDemand())
                    .as("period %d", t)
                    .isCloseTo(50, within(TOLERANCE));
        }
    }
}
