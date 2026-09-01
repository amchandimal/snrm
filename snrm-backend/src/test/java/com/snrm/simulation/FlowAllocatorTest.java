package com.snrm.simulation;

import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The per-period minimum-cost flow, on networks small enough to solve by hand.
 *
 * <p>These are the property-based tests for flow conservation, plus the four
 * behavioural claims {@code FlowAllocator}'s Javadoc makes and that nothing else would catch:
 * capacities bind, node splitting makes a node's capacity mean something, the penalty is
 * lexicographic rather than economic, and an unconstrained element cannot be partly disrupted.
 */
@DisplayName("FlowAllocator")
class FlowAllocatorTest {

    private static final double TOLERANCE = 1e-6;

    /** Replenishment off unless a test is about it, so each period is one question. */
    private static SimulationParams params(double safetyStockPriority) {
        return new SimulationParams(1, 1L, 10, 0, 0, false, false, safetyStockPriority, null, 1000,
                true, SimulationParams.ENGINE_VERSION);
    }

    private record Fixture(SimulationNetwork network, FlowAllocator allocator) {

        static Fixture of(NetworkGraph graph, double safetyStockPriority) {
            SimulationNetwork network = SimulationNetwork.of(graph);
            SimulationParams params = params(safetyStockPriority);
            return new Fixture(network,
                    new FlowAllocator(network, params, Quantiser.of(params.quantum(), 1e6)));
        }

        FlowSolution solve(double[] onHand, double[] nodeAvailability) {
            double[] linkAvailability = new double[network.linkCount()];
            Arrays.fill(linkAvailability, 1.0);
            return solve(onHand, nodeAvailability, linkAvailability);
        }

        FlowSolution solve(double[] onHand, double[] nodeAvailability, double[] linkAvailability) {
            double[] demand = new double[network.nodeCount()];
            for (int customer : network.customers()) {
                demand[customer] = network.demand(customer);
            }
            return allocator.allocate(nodeAvailability, linkAvailability, onHand,
                    new double[network.nodeCount()], demand);
        }

        double[] fullAvailability() {
            double[] availability = new double[network.nodeCount()];
            Arrays.fill(availability, 1.0);
            return availability;
        }

        double[] noStock() {
            return new double[network.nodeCount()];
        }
    }

    @Test
    @DisplayName("an unconstrained chain serves all of its demand")
    void servesEverythingItCan() {
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 100.0)
                .plant("PLANT-1", 80.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "PLANT-1", 100.0, 0)
                .link("PLANT-1", "CUST-1", 100.0, 0)
                .build(), 0);

        FlowSolution solution = fixture.solve(fixture.noStock(), fixture.fullAvailability());
        assertThat(solution.totalServed()).isCloseTo(50, within(TOLERANCE));
    }

    @Test
    @DisplayName("a node's capacity binds — which is what splitting the node is for")
    void nodeCapacityBinds() {
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 100.0)
                .plant("PLANT-1", 30.0)          // the bottleneck, and it is a *node*, not an arc
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "PLANT-1", 100.0, 0)
                .link("PLANT-1", "CUST-1", 100.0, 0)
                .build(), 0);

        FlowSolution solution = fixture.solve(fixture.noStock(), fixture.fullAvailability());
        assertThat(solution.totalServed()).isCloseTo(30, within(TOLERANCE));
    }

    @Test
    @DisplayName("a link's capacity binds too")
    void linkCapacityBinds() {
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 100.0)
                .plant("PLANT-1", 80.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "PLANT-1", 100.0, 0)
                .link("PLANT-1", "CUST-1", 20.0, 0)
                .build(), 0);

        assertThat(fixture.solve(fixture.noStock(), fixture.fullAvailability()).totalServed())
                .isCloseTo(20, within(TOLERANCE));
    }

    @Test
    @DisplayName("a plant cannot manufacture from nothing: only supply origins create material")
    void onlySupplyOriginsCreateMaterial() {
        // PLANT-1 has an inbound arc, so it is not an origin. Take SUP-1 offline and nothing can be
        // served, however much capacity the plant has.
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 100.0)
                .plant("PLANT-1", 80.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "PLANT-1", 100.0, 0)
                .link("PLANT-1", "CUST-1", 100.0, 0)
                .build(), 0);

        double[] availability = fixture.fullAvailability();
        availability[0] = 0;                                  // SUP-1 offline
        assertThat(fixture.solve(fixture.noStock(), availability).totalServed())
                .isCloseTo(0, within(TOLERANCE));
    }

    @Test
    @DisplayName("stock is dispatched even from a node taken fully offline — severity hits capacity")
    void stockSurvivesAnOutage() {
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 100.0)
                .plant("PLANT-1", 80.0).stock(60, 0, 0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "PLANT-1", 100.0, 0)
                .link("PLANT-1", "CUST-1", 100.0, 0)
                .build(), 0);

        double[] availability = fixture.fullAvailability();
        availability[1] = 0;                                  // PLANT-1 fully disrupted
        double[] onHand = {0, 60, 0};

        // Its production capacity is gone; the finished goods it already holds are not (the
        // capacity-availability multiplier). This is what makes inventory a resilience lever.
        FlowSolution solution = fixture.solve(onHand, availability);
        assertThat(solution.totalServed()).isCloseTo(50, within(TOLERANCE));
        assertThat(solution.stockDrawn()[1]).isCloseTo(50, within(TOLERANCE));
        assertThat(solution.throughput()[1]).isCloseTo(0, within(TOLERANCE));
    }

    @Test
    @DisplayName("an uncapped element cannot be partly disrupted, only taken offline")
    void unconstrainedElementIsAllOrNothing() {
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1")                             // no capacity ceiling
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "CUST-1", null, 0)              // nor has the arc
                .build(), 0);

        double[] half = fixture.fullAvailability();
        half[0] = 0.5;
        assertThat(fixture.solve(fixture.noStock(), half).totalServed())
                .as("halving a ceiling that does not bind changes nothing")
                .isCloseTo(50, within(TOLERANCE));

        double[] offline = fixture.fullAvailability();
        offline[0] = 0;
        assertThat(fixture.solve(fixture.noStock(), offline).totalServed())
                .isCloseTo(0, within(TOLERANCE));
    }

    @Test
    @DisplayName("the penalty is lexicographic: demand is served even when serving costs more than "
            + "the goods are worth")
    void penaltyIsNotEconomic() {
        // A unit is worth 1 and costs 40 to route. An economic penalty would leave the customer
        // unserved and FILL_RATE would depend on a price rather than on the network.
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 100.0).costs(0, 20)
                .plant("PLANT-1", 80.0).costs(0, 20)
                .customer("CUST-1", 50).unitValue(1)
                .link("SUP-1", "PLANT-1", 100.0, 0)
                .link("PLANT-1", "CUST-1", 100.0, 0)
                .build(), 0);

        assertThat(fixture.solve(fixture.noStock(), fixture.fullAvailability()).totalServed())
                .isCloseTo(50, within(TOLERANCE));
        assertThat(fixture.allocator().unmetPenalty())
                .as("must exceed the 40 per unit the only path costs")
                .isGreaterThan(40);
    }

    @Test
    @DisplayName("the cheapest path wins among those that serve: stock before production")
    void cheapestPathAmongServingSolutions() {
        Fixture fixture = Fixture.of(GraphFixtures.simulationChain(), 0);
        double[] onHand = {0, 100, 0};

        FlowSolution solution = fixture.solve(onHand, fixture.fullAvailability());
        // docs/simulation-verification.md §4.2: stock costs 1 a unit, production 7.
        assertThat(solution.stockDrawn()[1]).isCloseTo(50, within(TOLERANCE));
        assertThat(solution.throughput()[0]).as("SUP-1 produces nothing")
                .isCloseTo(0, within(TOLERANCE));
        assertThat(solution.variableCost()).isCloseTo(0, within(TOLERANCE));
        assertThat(solution.transportCost()).as("one arc, 50 units at 1")
                .isCloseTo(50, within(TOLERANCE));
    }

    @Test
    @DisplayName("flow is conserved: what a node takes in, it passes on, keeps or delivers")
    void flowIsConserved() {
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 200.0)
                .plant("PLANT-1", 150.0)
                .dc("DC-1", 100.0)
                .dc("DC-2", 100.0)
                .customer("CUST-1", 60).unitValue(20)
                .customer("CUST-2", 40).unitValue(20)
                .link("SUP-1", "PLANT-1", 200.0, 0)
                .link("PLANT-1", "DC-1", 100.0, 0)
                .link("PLANT-1", "DC-2", 100.0, 0)
                .link("DC-1", "CUST-1", 100.0, 0)
                .link("DC-2", "CUST-2", 100.0, 0)
                .build(), 0);

        FlowSolution solution = fixture.solve(fixture.noStock(), fixture.fullAvailability());
        SimulationNetwork network = fixture.network();

        for (int i = 0; i < network.nodeCount(); i++) {
            double in = solution.throughput()[i] + solution.stockDrawn()[i];
            double out = solution.served()[i] + solution.heldNow()[i] + solution.heldLater()[i];
            for (int e = 0; e < network.linkCount(); e++) {
                if (network.linkSource(e) == i) {
                    out += solution.linkFlow()[e];
                }
            }
            assertThat(in).as("conservation at node %d (%s)", i, network.nodeName(i))
                    .isCloseTo(out, within(TOLERANCE));
        }
        assertThat(solution.totalServed()).isCloseTo(100, within(TOLERANCE));
    }

    @Test
    @DisplayName("no demand and no replenishment need means no solve and an all-zero solution")
    void emptyPeriodShortCircuits() {
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 100.0)
                .customer("CUST-1", 0).unitValue(20)
                .link("SUP-1", "CUST-1", 100.0, 0)
                .build(), 0);

        FlowSolution solution = fixture.solve(fixture.noStock(), fixture.fullAvailability());
        assertThat(solution.totalServed()).isZero();
        assertThat(solution.variableCost()).isZero();
        assertThat(solution.transportCost()).isZero();
    }

    @Test
    @DisplayName("replenishment pulls stock toward the order-up-to level, never past a customer")
    void replenishmentIsSecondPriority() {
        // SUP-1 can move 60 a period against a demand of 50. The customer takes its 50 first; the
        // remaining 10 goes toward DC-1's order-up-to level rather than being left idle.
        Fixture fixture = Fixture.of(GraphFixtures.network()
                .supplier("SUP-1", 60.0)
                .dc("DC-1", 100.0).stock(0, 200, 0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "DC-1", 100.0, 0)
                .link("DC-1", "CUST-1", 100.0, 0)
                .build(), 0.1);

        FlowSolution solution = fixture.solve(fixture.noStock(), fixture.fullAvailability());
        assertThat(solution.totalServed()).as("the customer is served in full first")
                .isCloseTo(50, within(TOLERANCE));
        // Where the spare 10 comes to rest is not asserted: every node in this chain wants stock and
        // every route to one costs the same, so the solver is genuinely indifferent between them.
        // What the priority guarantees is that the 10 is put to work rather than left idle, and that
        // it never comes out of the customer's 50.
        assertThat(Arrays.stream(solution.heldNow()).sum()).as("the spare 10 becomes stock somewhere")
                .isCloseTo(10, within(TOLERANCE));
    }
}
