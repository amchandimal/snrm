package com.snrm.simulation;

import org.jgrapht.Graph;
import org.jgrapht.alg.flow.mincost.CapacityScalingMinimumCostFlow;
import org.jgrapht.alg.flow.mincost.MinimumCostFlowProblem;
import org.jgrapht.alg.interfaces.MinimumCostFlowAlgorithm;
import org.jgrapht.graph.DirectedWeightedPseudograph;

import java.util.HashMap;
import java.util.Map;

/**
 * Step ③ of the per-period loop: the minimum-cost flow (JGraphT).
 *
 * <blockquote>"Within a period, allocation is solved as a minimum-cost flow on the capacity-adjusted
 * graph (JGraphT min-cost-flow), with unserved demand absorbed by a penalty arc. This keeps each
 * period polynomial-time and makes the behavioural assumption explicit and defensible: the network
 * re-routes optimally within a period, so measured resilience is a property of the configuration,
 * not of a routing heuristic. Inventory at a node acts as an additional supply source in the period
 * after it is stocked; lead times delay flow arrival by the link's lead-time in periods (modelled as
 * pipeline inventory)."</blockquote>
 *
 * <h2>The construction</h2>
 *
 * <p>Every node is split into four vertices, which is what lets one flow problem express both "how
 * much can pass through this node" and "material that arrives here may stop here":
 *
 * <pre>
 *            production                    pass-through
 *   S ─────────────────▶ i_in ──cap,varCost──▶ i_mid ────────▶ i_out ──▶ outbound links, demand
 *                          ▲                     │                ▲
 *          inbound lead-0  │                     │ stop here      │ on-hand stock
 *              links ──────┘                     ▼                │
 *                                             i_hold ◀── inbound lead-time links
 *                                                │
 *                                                ▼  capped at this node's safety-stock shortfall
 *                                               T_R
 * </pre>
 *
 * <table border="1">
 *   <caption>Every arc, its capacity and its cost</caption>
 *   <tr><th>Arc</th><th>When</th><th>Capacity</th><th>Cost / unit</th></tr>
 *   <tr><td>{@code S → i_in}</td><td>i is a supply origin</td><td>unbounded</td><td>0</td></tr>
 *   <tr><td>{@code i_in → i_mid}</td><td>always</td><td>availability × nominal node capacity</td>
 *       <td>{@code varCost(i)}</td></tr>
 *   <tr><td>{@code i_mid → i_out}</td><td>{@code processingPeriods(i) == 0}</td><td>unbounded</td>
 *       <td>0</td></tr>
 *   <tr><td>{@code i_mid → i_hold}</td><td>i needs replenishing</td><td>unbounded</td><td>0</td></tr>
 *   <tr><td>{@code S → i_out}</td><td>always</td><td>on-hand inventory</td><td>0</td></tr>
 *   <tr><td>{@code u_out → v_in}</td><td>link, lead 0</td><td>availability × link capacity</td>
 *       <td>{@code unitCost(e)}</td></tr>
 *   <tr><td>{@code u_out → v_hold}</td><td>link, lead ≥ 1</td><td>availability × link capacity</td>
 *       <td>{@code unitCost(e)}</td></tr>
 *   <tr><td>{@code c_out → T_D}</td><td>c is a customer</td><td>realised demand</td><td>0</td></tr>
 *   <tr><td>{@code i_hold → T_R}</td><td>need &gt; 0</td><td>order-up-to shortfall</td><td>0</td></tr>
 *   <tr><td><strong>{@code S → T_D}</strong></td><td>the penalty arc</td><td>total demand</td>
 *       <td>{@link #unmetPenalty}</td></tr>
 *   <tr><td>{@code S → T_R}</td><td>always</td><td>total shortfall</td>
 *       <td>{@code safetyStockPriority × unmetPenalty}</td></tr>
 * </table>
 *
 * <p>Supplies balance by construction — {@code S} holds {@code D + R}, {@code T_D} takes
 * {@code −D} and {@code T_R} takes {@code −R} — and the two penalty arcs can absorb all of both, so
 * the problem is <strong>always feasible</strong>. A period in which nothing can move is answered by
 * the penalty arcs saturating, not by an exception.
 *
 * <h2>Why the penalty is big-M and not the price of the goods</h2>
 *
 * <p>The penalty could be priced at what the unmet units were worth, which would make the flow's
 * objective the period's economic cost outright. It is deliberately not, and the reason is that
 * {@code FILL_RATE} would then measure profitability rather than capability: a customer whose
 * {@code product.unit_value} happened to sit below the cost of the cheapest path to them would be
 * left unserved <em>by choice</em>, the fill rate would fall, and the resilience metrics
 * would move when a price changed and the network did not. That is indefensible in a resilience
 * study.
 *
 * <p>So {@link #unmetPenalty} strictly exceeds the most expensive path in the network, which makes
 * the objective lexicographic: <strong>serve as much demand as physically possible, and among the
 * solutions that do, route at least cost.</strong> The economic cost of a shortage is accounted
 * separately, at the customer's unit value, in {@code SimulationEngine} — so the money is still the
 * product's money, it simply is not what decides the routing.
 *
 * <p>The replenishment sink sits between the two: filling a unit of safety-stock shortfall is worth
 * {@code safetyStockPriority} of serving a unit of demand (0.1 by default), and it too dominates
 * routing cost — so the network pre-positions stock with whatever capacity a customer did not need,
 * and never at a customer's expense.
 *
 * <h2>Integers</h2>
 *
 * <p>JGraphT's minimum-cost flow takes {@code int} supplies and capacities; SNRM's quantities are
 * continuous. {@link Quantiser} carries them on a fixed-point grid — see that class for which way
 * each rounds and why.
 *
 * <h2>Two things about JGraphT 1.5.2 that are not in its Javadoc</h2>
 *
 * <p><strong>The arc costs come from the graph's edge weights, not from the problem's cost
 * function.</strong> {@code MinimumCostFlowProblem} declares {@code getArcCosts()}, and
 * {@code CapacityScalingMinimumCostFlow} never calls it: both its {@code init} and its {@code finish}
 * read {@code graph.getEdgeWeight(edge)}. So the graph here must be a <em>weighted</em> one and every
 * arc's weight must be set, or every arc silently costs {@code Graph.DEFAULT_EDGE_WEIGHT} = 1. The
 * symptom is unmistakable once seen and baffling until then: with every arc at cost 1, the one-hop
 * penalty arc beats every real path, the optimum is to serve nothing, and the whole suite reports a
 * fill rate of 0. The cost function is still passed — it is the interface's contract — but it is
 * passed as {@code graph::getEdgeWeight}, so the two cannot disagree.
 *
 * <p><strong>{@code CapacityScalingMinimumCostFlow.Node} numbers itself from a mutable static</strong>
 * that the algorithm's constructor resets, so two instances built concurrently race. It is harmless
 * here — the id is read only by {@code Node.toString()} and never by the algorithm — which is what
 * makes the parallel replications safe. It is recorded because that is not obvious from
 * the outside, and because it would stop being harmless if a future version used the id for
 * anything.
 */
final class FlowAllocator {

    private static final int SUPER_SOURCE = 0;
    private static final int DEMAND_SINK = 1;
    private static final int REPLENISH_SINK = 2;
    private static final int NODE_BASE = 3;
    private static final int VERTICES_PER_NODE = 4;

    private final SimulationNetwork network;
    private final Quantiser quantiser;
    private final double safetyStockPriority;

    /**
     * Strictly greater than the cost of any path through the network, so serving demand always beats
     * refusing it. See the class note on why this is not the goods' price.
     */
    private final double unmetPenalty;

    FlowAllocator(SimulationNetwork network, SimulationParams params, Quantiser quantiser) {
        this.network = network;
        this.quantiser = quantiser;
        this.safetyStockPriority = params.safetyStockPriority();
        this.unmetPenalty = dominatingPenalty(network);
    }

    /**
     * The cheapest way to move this period's material toward this period's demand.
     *
     * @param nodeAvailability multiplier per node from {@link AvailabilityModel}
     * @param linkAvailability multiplier per link
     * @param onHand           inventory at each node at the start of the period, arrivals included
     * @param inTransit        material already on its way to each node, so replenishment does not
     *                         re-order what is already coming (the base-stock netting rule)
     * @param demand           realised demand at each node this period
     */
    FlowSolution allocate(double[] nodeAvailability, double[] linkAvailability, double[] onHand,
            double[] inTransit, double[] demand) {

        int nodeCount = network.nodeCount();
        int linkCount = network.linkCount();

        int[] demandUnits = new int[nodeCount];
        int[] replenishUnits = new int[nodeCount];
        long totalDemandUnits = 0;
        long totalReplenishUnits = 0;
        for (int i = 0; i < nodeCount; i++) {
            demandUnits[i] = quantiser.roundUnits(demand[i]);
            totalDemandUnits += demandUnits[i];
            if (safetyStockPriority > 0) {
                // Order-up-to, netted against what is already on hand and already coming: a node
                // with a long inbound lead time must not re-order the same shortfall every period
                // while its first shipment is still in transit. See
                // SimulationNetwork.computeReplenishTargets for what the target is and why.
                double shortfall = network.replenishTarget(i) - onHand[i] - inTransit[i];
                replenishUnits[i] = quantiser.floorUnits(shortfall);
                totalReplenishUnits += replenishUnits[i];
            }
        }

        if (totalDemandUnits == 0 && totalReplenishUnits == 0) {
            // Nothing to pull material through the network. Building and solving a flow problem
            // whose every answer is zero is a slower way of saying so.
            return FlowSolution.empty(network);
        }

        // Weighted, because the algorithm reads costs from the edge weights — see the class note.
        Graph<Integer, FlowArc> graph = new DirectedWeightedPseudograph<>(FlowArc.class);
        Map<FlowArc, Integer> capacity = new HashMap<>();

        // Nothing can carry more than the whole problem's supply, so any capacity above it is
        // "unconstrained" in the only sense that matters. Using that as the ceiling rather than
        // Quantiser.UNBOUNDED keeps the solver's capacity-scaling parameter at the scale of the
        // actual flow: with a ceiling of half the int range it would spend a dozen scaling phases
        // stepping down to a problem measured in thousands.
        int ceiling = (int) (totalDemandUnits + totalReplenishUnits);

        graph.addVertex(SUPER_SOURCE);
        // A sink is added only when something drains into it. An isolated vertex with zero supply
        // is harmless in principle; leaving it out keeps the problem the solver is handed to exactly
        // the one being asked — which matters most for a run with safetyStockPriority = 0, where
        // replenishment is switched off entirely and the whole T_R half of the graph is absent.
        if (totalDemandUnits > 0) {
            graph.addVertex(DEMAND_SINK);
        }
        if (totalReplenishUnits > 0) {
            graph.addVertex(REPLENISH_SINK);
        }
        for (int i = 0; i < nodeCount; i++) {
            graph.addVertex(in(i));
            graph.addVertex(mid(i));
            graph.addVertex(out(i));
            graph.addVertex(hold(i));
        }

        for (int i = 0; i < nodeCount; i++) {
            double available = adjusted(network.nodeCapacity(i), nodeAvailability[i]);

            if (network.isSupplyOrigin(i)) {
                // Unbounded here; the throughput arc below is the single place a node's capacity is
                // expressed, so a disruption has one place to bite.
                add(graph, capacity, ceiling, SUPER_SOURCE, in(i),
                        new FlowArc(FlowArc.Kind.PRODUCTION, i), Quantiser.UNBOUNDED, 0);
            }
            add(graph, capacity, ceiling, in(i), mid(i),
                    new FlowArc(FlowArc.Kind.NODE_THROUGHPUT, i),
                    quantiser.floorUnits(available), network.varCost(i));

            if (network.processingPeriods(i) == 0) {
                add(graph, capacity, ceiling, mid(i), out(i),
                        new FlowArc(FlowArc.Kind.NODE_PASS, i), Quantiser.UNBOUNDED, 0);
            }
            if (replenishUnits[i] > 0) {
                // The route by which material stops here. Present whatever the dwell: with none,
                // material that arrived may still choose to stay; with one, this is the only way out
                // of mid and the arrival is deferred by the caller. Omitted when this node needs
                // nothing, because the arc's only outlet is the replenishment sink below — flow
                // into a dead end is forced to zero anyway, and the solver need not discover that.
                add(graph, capacity, ceiling, mid(i), hold(i),
                        new FlowArc(FlowArc.Kind.NODE_HOLD, i), Quantiser.UNBOUNDED, 0);
                add(graph, capacity, ceiling, hold(i), REPLENISH_SINK,
                        new FlowArc(FlowArc.Kind.REPLENISH, i), replenishUnits[i], 0);
            }

            int stock = quantiser.floorUnits(onHand[i]);
            if (stock > 0) {
                // Enters at out(i), not at in(i): stock is finished goods ready to ship, and acts
                // as an additional supply source — i.e. substitute for production,
                // which is what the node's capacity limits. A consequence worth stating: an element
                // taken fully offline can still dispatch what it already holds, because severity is
                // a reduction in *capacity* availability, not a loss of stock. That is
                // what makes inventory a resilience lever at all.
                add(graph, capacity, ceiling, SUPER_SOURCE, out(i),
                        new FlowArc(FlowArc.Kind.STOCK, i), stock, 0);
            }
            if (demandUnits[i] > 0) {
                add(graph, capacity, ceiling, out(i), DEMAND_SINK,
                        new FlowArc(FlowArc.Kind.DEMAND, i), demandUnits[i], 0);
            }
        }

        for (int e = 0; e < linkCount; e++) {
            if (!network.linkIsUsable(e)) {
                continue;
            }
            int source = network.linkSource(e);
            int target = network.linkTarget(e);
            double available = adjusted(network.linkCapacity(e), linkAvailability[e]);
            // Lead 0 lands at the target's inbound vertex and can move on through it in the same
            // period; lead ≥ 1 lands in the target's hold, and the caller defers the arrival by the
            // link's lead time — the pipeline inventory.
            int head = network.leadTimePeriods(e) == 0 ? in(target) : hold(target);
            add(graph, capacity, ceiling, out(source), head,
                    new FlowArc(FlowArc.Kind.LINK, e),
                    quantiser.floorUnits(available), network.linkUnitCost(e));
        }

        // The penalty arcs. Both are sized to their sink's whole requirement, so the
        // problem can always be balanced by refusing everything — which is what makes it feasible in
        // a period where nothing at all can move.
        add(graph, capacity, ceiling, SUPER_SOURCE, DEMAND_SINK,
                new FlowArc(FlowArc.Kind.DEMAND_PENALTY, -1),
                (int) totalDemandUnits, unmetPenalty);
        add(graph, capacity, ceiling, SUPER_SOURCE, REPLENISH_SINK,
                new FlowArc(FlowArc.Kind.REPLENISH_PENALTY, -1),
                (int) totalReplenishUnits, unmetPenalty * safetyStockPriority);

        Map<Integer, Integer> supply = new HashMap<>();
        supply.put(SUPER_SOURCE, (int) (totalDemandUnits + totalReplenishUnits));
        supply.put(DEMAND_SINK, (int) -totalDemandUnits);
        supply.put(REPLENISH_SINK, (int) -totalReplenishUnits);

        MinimumCostFlowProblem<Integer, FlowArc> problem =
                new MinimumCostFlowProblem.MinimumCostFlowProblemImpl<>(
                        graph,
                        vertex -> supply.getOrDefault(vertex, 0),
                        arc -> capacity.getOrDefault(arc, 0),   // upper bounds
                        arc -> 0,                               // lower bounds
                        // The costs the algorithm actually reads are the edge weights (see the class
                        // note); handing it the same function is what stops the two from drifting.
                        graph::getEdgeWeight);

        MinimumCostFlowAlgorithm.MinimumCostFlow<FlowArc> solved =
                new CapacityScalingMinimumCostFlow<Integer, FlowArc>().getMinimumCostFlow(problem);
        return decode(solved.getFlowMap(), nodeCount, linkCount);
    }

    /**
     * Reads the solved arc flows back into the quantities the inventory balance needs.
     *
     * <p>One pass over the arcs, dispatching on {@link FlowArc.Kind}. Nothing is inferred from
     * conservation here — every quantity is a flow the solver reported — so a construction error
     * shows up as an imbalance in {@code SimulationEngine}'s check rather than being papered over.
     */
    private FlowSolution decode(Map<FlowArc, Double> flows, int nodeCount, int linkCount) {
        double[] served = new double[nodeCount];
        double[] throughput = new double[nodeCount];
        double[] stockDrawn = new double[nodeCount];
        double[] heldNow = new double[nodeCount];
        double[] heldLater = new double[nodeCount];
        double[] linkFlow = new double[linkCount];
        double variableCost = 0;
        double transportCost = 0;
        double totalServed = 0;

        for (Map.Entry<FlowArc, Double> entry : flows.entrySet()) {
            FlowArc arc = entry.getKey();
            double units = entry.getValue() == null ? 0 : entry.getValue();
            if (units <= 0) {
                continue;
            }
            double quantity = quantiser.toQuantity(units);
            int i = arc.index();
            switch (arc.kind()) {
                case NODE_THROUGHPUT -> {
                    throughput[i] = quantity;
                    variableCost += quantity * network.varCost(i);
                }
                case STOCK -> stockDrawn[i] = quantity;
                case NODE_HOLD -> {
                    // The same arc means two different things depending on the node's dwell: with
                    // none, the material is at the node now; with one, it is inside the node and
                    // lands after processingPeriods.
                    if (network.processingPeriods(i) == 0) {
                        heldNow[i] = quantity;
                    } else {
                        heldLater[i] = quantity;
                    }
                }
                case LINK -> {
                    linkFlow[i] = quantity;
                    transportCost += quantity * network.linkUnitCost(i);
                }
                case DEMAND -> {
                    served[i] = quantity;
                    totalServed += quantity;
                }
                case PRODUCTION, NODE_PASS, REPLENISH, DEMAND_PENALTY, REPLENISH_PENALTY -> {
                    // Accounted through the arcs above: production and pass-through are already
                    // counted as throughput, replenishment as heldNow/heldLater, and the two
                    // penalties as the shortfall between demand and served.
                }
            }
        }
        return new FlowSolution(served, throughput, stockDrawn, heldNow, heldLater, linkFlow,
                variableCost, transportCost, totalServed);
    }

    /**
     * A capacity after the period's availability multiplier.
     *
     * <p><strong>An unconstrained element cannot be partly disrupted.</strong> Halving a ceiling
     * that does not bind changes nothing — {@code ∞ × 0.5} is still {@code ∞} — so a severity
     * strictly between 0 and 1 leaves it unconstrained, and only a total outage takes it offline.
     * Stated here rather than left to floating-point arithmetic, because the alternative is a
     * severity that silently does nothing.
     *
     * <p>Package-private rather than private because {@code SimulationEngine} divides by the same
     * number to record a link's utilisation: two copies of this rule would eventually let
     * the capacity a run <em>used</em> and the capacity it <em>reports</em> disagree.
     */
    static double adjusted(double nominal, double availability) {
        if (Double.isInfinite(nominal)) {
            return availability <= 0 ? 0 : Double.POSITIVE_INFINITY;
        }
        return nominal * availability;
    }

    /**
     * A per-unit penalty strictly greater than any path's cost, so refusing to serve is never
     * cheaper than serving.
     *
     * <p>The sum of every variable and unit cost in the network bounds any simple path from above —
     * a path visits each node and each arc at most once — so one more than that dominates. The
     * {@code +1} also covers a network whose costs are all zero, where the penalty would otherwise
     * be 0 and the solver would be indifferent between serving and not.
     */
    private static double dominatingPenalty(SimulationNetwork network) {
        double total = 0;
        for (int i = 0; i < network.nodeCount(); i++) {
            total += Math.max(0, network.varCost(i));
        }
        for (int e = 0; e < network.linkCount(); e++) {
            total += Math.max(0, network.linkUnitCost(e));
        }
        // JGraphT refuses an edge whose weight reaches COST_INF (1e9) as "infinite cost", so the
        // penalty has to stay below it. A network whose costs sum past a tenth of that is far beyond
        // anything FR-04 contemplates, and clamping is the right failure: the ordering "serve, then
        // route cheaply" survives for every path except ones dearer than the clamp, whereas letting
        // the weight through would make the whole period throw.
        return Math.min(total + 1, PENALTY_CEILING);
    }

    /** Comfortably below {@code CapacityScalingMinimumCostFlow.COST_INF}; see above. */
    private static final double PENALTY_CEILING = 1e8;

    /**
     * Adds one arc, with its capacity and — as an edge weight — its cost.
     *
     * <p>The weight is the cost, not a second copy of it: JGraphT's solver reads
     * {@code graph.getEdgeWeight(edge)} and ignores the problem's cost function entirely (see the
     * class note). Setting it here, in the one place an arc is created, is what makes that
     * impossible to forget.
     */
    private static void add(Graph<Integer, FlowArc> graph, Map<FlowArc, Integer> capacities,
            int ceiling, int from, int to, FlowArc arc, int capacity, double cost) {
        if (capacity <= 0) {
            // A zero-capacity arc is one the solver would carry through every scaling phase to
            // conclude it can send nothing along it. Leaving it out is the same problem, smaller.
            return;
        }
        graph.addEdge(from, to, arc);
        graph.setEdgeWeight(arc, cost);
        // An arc cannot carry more than the problem's whole supply, so clamping here changes no
        // answer and keeps the solver's scaling parameter at the scale of the actual flow.
        capacities.put(arc, Math.min(capacity, ceiling));
    }

    private static int in(int nodeIndex) {
        return NODE_BASE + VERTICES_PER_NODE * nodeIndex;
    }

    private static int mid(int nodeIndex) {
        return NODE_BASE + VERTICES_PER_NODE * nodeIndex + 1;
    }

    private static int out(int nodeIndex) {
        return NODE_BASE + VERTICES_PER_NODE * nodeIndex + 2;
    }

    private static int hold(int nodeIndex) {
        return NODE_BASE + VERTICES_PER_NODE * nodeIndex + 3;
    }

    /** The dominating penalty, exposed so tests can assert the lexicographic property. */
    double unmetPenalty() {
        return unmetPenalty;
    }
}
