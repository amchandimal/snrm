package com.snrm.simulation;

import com.snrm.network.GraphLink;
import com.snrm.network.GraphNode;
import com.snrm.network.GraphNodeProduct;
import com.snrm.network.NetworkGraph;
import com.snrm.network.NodeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code NetworkGraph} snapshot flattened into primitive arrays for the per-period loop, and
 * reduced to a single commodity.
 *
 * <p>Built once per run and shared, unchanged, by every replication — including the paired baseline
 * set — so the per-period cost is a min-cost-flow solve and nothing else. The known risk is
 * that min-cost-flow per period becomes slow on large networks × replications, and the first
 * mitigation for it is primitive-indexed snapshot graphs; this is that.
 *
 * <h2>One commodity, and what that means</h2>
 *
 * <p>Every {@code (node, product)} pair has its own demand, inventory, safety stock and
 * holding cost, so the model is multi-product. The <em>allocation</em> is not: a per-period
 * multi-commodity minimum-cost flow with shared node and arc capacities is NP-hard in general and
 * would break the "keeps each period polynomial-time" guarantee, and JGraphT supplies no
 * multi-commodity solver. Solving one commodity at a time in a fixed product order would keep the
 * complexity but lose optimality, which is the property the model leans on — the network re-routes
 * optimally within a period, so measured resilience is a property of the configuration, not of a
 * routing heuristic.
 *
 * <p>So products are aggregated here, and the engine is single-commodity:
 *
 * <table border="1">
 *   <caption>How each per-product quantity is aggregated to the node</caption>
 *   <tr><th>Quantity</th><th>Aggregation</th></tr>
 *   <tr><td>demand, initial inventory, safety stock</td><td>sum</td></tr>
 *   <tr><td>holding cost per unit-period</td><td>stock-weighted mean over products</td></tr>
 *   <tr><td>unit value (the price of unmet demand)</td><td>demand-weighted mean over products</td></tr>
 * </table>
 *
 * <p><strong>This is a modelling assumption, not a shortcut, and the result model shares
 * it:</strong> {@code RUN_TIMESERIES} has no product column, and every simulated metric —
 * fill rate, service level, TTR, loss area, the three cost metrics, the resilience index — is defined
 * over the network's totals. Nothing in Phase 1 can express a per-product answer, so nothing is lost
 * on the way out; what is lost is the ability to say that a shortage fell on the high-value product
 * rather than the low-value one. {@code docs/multi-commodity-extension.md} specifies the extension
 * that recovers it.
 *
 * <h2>Supply origins</h2>
 *
 * <p>Material enters the model at nodes that are {@code SUPPLIER} or {@code PLANT} <em>and have no
 * inbound arc</em> — the set is computed once here, on the intact graph, and held fixed for the whole
 * run. That is verbatim the convention {@code com.snrm.metrics.topological} already states and
 * {@code docs/metric-verification.md} §2 works through, and sharing it is what stops the two engines
 * from disagreeing about where a network's supply begins. The consequence inside a period is that a
 * plant fed by a supplier can only pass through what reaches it: its capacity is a throughput
 * ceiling, not a licence to manufacture from nothing.
 */
public final class SimulationNetwork {

    private final long networkId;
    private final int nodeCount;
    private final int linkCount;

    // ------------------------------------------------------------------------- nodes
    private final long[] nodeIds;
    private final String[] nodeNames;
    private final NodeType[] nodeTypes;
    /** Per-period throughput ceiling; {@link Double#POSITIVE_INFINITY} where unconstrained. */
    private final double[] nodeCapacity;
    private final long[] processingPeriods;
    private final double[] fixedCost;
    private final double[] varCost;
    private final double[] nodeFailureProb;
    private final double[] demand;
    private final double[] initialInventory;
    private final double[] safetyStock;
    private final double[] holdingCost;
    private final double[] shortagePenalty;
    private final double[] replenishTarget;
    private final boolean[] supplyOrigin;
    private final int[] customers;

    // ------------------------------------------------------------------------- links
    private final long[] linkIds;
    private final int[] linkSource;
    private final int[] linkTarget;
    private final double[] linkCapacity;
    private final long[] leadTimePeriods;
    private final double[] linkUnitCost;
    private final double[] linkFailureProb;

    // ---------------------------------------------------------------------- derived
    private final Map<Long, Integer> indexByNodeId;
    private final Map<Long, Integer> indexByLinkId;
    private final double totalDemandPerPeriod;
    private final double totalSafetyStock;
    private final double totalFixedCost;
    private final int pipelineDepth;

    private SimulationNetwork(NetworkGraph graph) {
        this.networkId = graph.networkId();
        List<GraphNode> nodes = graph.nodes();
        List<GraphLink> links = graph.links();
        this.nodeCount = nodes.size();
        this.linkCount = links.size();

        nodeIds = new long[nodeCount];
        nodeNames = new String[nodeCount];
        nodeTypes = new NodeType[nodeCount];
        nodeCapacity = new double[nodeCount];
        processingPeriods = new long[nodeCount];
        fixedCost = new double[nodeCount];
        varCost = new double[nodeCount];
        nodeFailureProb = new double[nodeCount];
        demand = new double[nodeCount];
        initialInventory = new double[nodeCount];
        safetyStock = new double[nodeCount];
        holdingCost = new double[nodeCount];
        shortagePenalty = new double[nodeCount];
        replenishTarget = new double[nodeCount];
        supplyOrigin = new boolean[nodeCount];
        indexByNodeId = new HashMap<>(nodeCount * 2);

        double demandTotal = 0;
        double safetyTotal = 0;
        double fixedTotal = 0;
        List<Integer> customerIndices = new ArrayList<>();

        for (int i = 0; i < nodeCount; i++) {
            GraphNode node = nodes.get(i);
            nodeIds[i] = node.id();
            nodeNames[i] = node.name();
            nodeTypes[i] = node.type();
            // A null capacity is "no ceiling"; infinity is the honest arithmetic form of that, and
            // the flow builder turns it into Quantiser.UNBOUNDED rather than an integer overflow.
            nodeCapacity[i] = node.capacityPerPeriod() == null
                    ? Double.POSITIVE_INFINITY
                    : Math.max(0, node.capacityPerPeriod());
            processingPeriods[i] = Math.max(0, node.processingPeriods());
            fixedCost[i] = node.fixedCost();
            varCost[i] = node.varCost();
            nodeFailureProb[i] = clampProbability(node.failureProb());
            indexByNodeId.put(node.id(), i);

            aggregateProducts(i, node);
            // Demand sits on CUSTOMER nodes, following the convention the topological
            // suite already states. Demand entered on a plant is ignored here exactly as the
            // importer warns it will be.
            if (node.type() != NodeType.CUSTOMER) {
                demand[i] = 0;
            } else {
                customerIndices.add(i);
            }
            demandTotal += demand[i];
            safetyTotal += safetyStock[i];
            fixedTotal += fixedCost[i];
        }

        customers = customerIndices.stream().mapToInt(Integer::intValue).toArray();
        totalDemandPerPeriod = demandTotal;
        totalSafetyStock = safetyTotal;
        totalFixedCost = fixedTotal;

        linkIds = new long[linkCount];
        linkSource = new int[linkCount];
        linkTarget = new int[linkCount];
        linkCapacity = new double[linkCount];
        leadTimePeriods = new long[linkCount];
        linkUnitCost = new double[linkCount];
        linkFailureProb = new double[linkCount];
        indexByLinkId = new HashMap<>(linkCount * 2);

        boolean[] hasInbound = new boolean[nodeCount];
        long maxDelay = 0;
        for (int e = 0; e < linkCount; e++) {
            GraphLink link = links.get(e);
            Integer source = indexByNodeId.get(link.sourceNodeId());
            Integer target = indexByNodeId.get(link.targetNodeId());
            linkIds[e] = link.id();
            // -1 for a dangling endpoint. The foreign keys make it impossible and both collections
            // were read in one transaction, but a snapshot must not be the thing that throws on data
            // the database accepted — NetworkGraph makes the same choice for the same reason.
            linkSource[e] = source == null ? -1 : source;
            linkTarget[e] = target == null ? -1 : target;
            linkCapacity[e] = link.capacityPerPeriod() == null
                    ? Double.POSITIVE_INFINITY
                    : Math.max(0, link.capacityPerPeriod());
            leadTimePeriods[e] = Math.max(0, link.leadTimePeriods());
            linkUnitCost[e] = link.unitCost();
            linkFailureProb[e] = clampProbability(link.failureProb());
            indexByLinkId.put(link.id(), e);
            if (linkTarget[e] >= 0) {
                hasInbound[linkTarget[e]] = true;
            }
            maxDelay = Math.max(maxDelay, leadTimePeriods[e]);
        }

        for (int i = 0; i < nodeCount; i++) {
            supplyOrigin[i] = isSupplySide(nodeTypes[i]) && !hasInbound[i];
            maxDelay = Math.max(maxDelay, processingPeriods[i]);
        }
        // One past the longest delay, so a shipment dispatched in the last period still has a slot
        // to land in even though the horizon ends before it arrives.
        pipelineDepth = (int) Math.min(Integer.MAX_VALUE - 1, maxDelay) + 1;

        computeReplenishTargets();
    }

    /**
     * The order-up-to level each node aims at: its safety stock, plus enough of the demand it covers
     * to bridge its own replenishment delay.
     *
     * <pre>
     *   coverage(i)        = min( demand of the CUSTOMERs reachable from i , i's own capacity )
     *   replenishDelay(i)  = longest inbound lead time + i's own processing dwell, in periods
     *   replenishTarget(i) = safetyStock(i) + coverage(i) × ( 1 + replenishDelay(i) )
     * </pre>
     *
     * <p><strong>Why safety stock alone is not enough.</strong> A unit crossing a link with a lead
     * time cannot serve demand in the period it is dispatched — it lands as the target's inventory
     * later, which is what pipeline inventory means. So downstream nodes are served out of
     * stock, and something has to pull that stock down the network. A customer fed by a one-period
     * link and carrying no safety stock would target nothing, receive nothing, and be recorded as
     * unservable: a network the topological suite calls perfectly well connected would report a fill
     * rate of zero. Every sample network under {@code samples/} has exactly that shape.
     *
     * <p><strong>Why one period of coverage is not enough either.</strong> With a target of exactly
     * one period's demand, a node that starts a period at its target and ships all of it ends at
     * zero, orders the shortfall, and waits out the lead time with nothing — so it serves every
     * <em>other</em> period and the network oscillates instead of settling. Covering the delay is
     * what makes the policy a base-stock policy rather than a one-shot order, and it is what lets the
     * fill rate reach a steady state after the warm-up. {@code InventoryPipelineTest} pins that
     * behaviour on a two-echelon chain.
     *
     * <p>Safety stock is then what its name says: a buffer <em>above</em> the working level. That is
     * also what makes it a meaningful Phase 2 redundancy lever rather than the only thing
     * keeping the network alive.
     *
     * <p><strong>The longest inbound lead time, not the shortest.</strong> Which route a
     * replenishment actually takes is the flow problem's decision, made on cost rather than on speed,
     * so the coverage has to assume the slow one. Under-covering makes a well-designed network report
     * a fill rate it could have achieved — a false negative, and the one direction a resilience study
     * must not err in. Over-covering costs holding cost, which is at least visible in
     * {@code TOTAL_COST}.
     *
     * <p><strong>The capacity cap matters.</strong> Reachable demand is capped at the node's own
     * throughput, because a node cannot usefully hold more than it can move in a period, and an
     * uncapped target at a hub would have the whole network's demand ordered into one warehouse.
     *
     * <p>Reachability is computed once, on the intact graph, by one breadth-first search per node —
     * {@code O(n·m)} for the whole run, against a min-cost flow per period per replication. Like the
     * supply-origin set, it is <em>not</em> recomputed when a disruption removes capacity: the target
     * is what the network is configured to hold, and a policy that re-planned itself around every
     * outage would be a different (and much stronger) model than this engine implements.
     */
    private void computeReplenishTargets() {
        long[] inboundLead = new long[nodeCount];
        for (int e = 0; e < linkCount; e++) {
            int target = linkTarget[e];
            if (target >= 0) {
                inboundLead[target] = Math.max(inboundLead[target], leadTimePeriods[e]);
            }
        }
        for (int i = 0; i < nodeCount; i++) {
            double coverage = Math.min(reachableCustomerDemand(i), nodeCapacity[i]);
            long delay = inboundLead[i] + processingPeriods[i];
            replenishTarget[i] = safetyStock[i] + coverage * (1 + delay);
        }
    }

    /** Total demand of the CUSTOMER nodes reachable from {@code start}, itself included. */
    private double reachableCustomerDemand(int start) {
        boolean[] seen = new boolean[nodeCount];
        int[] queue = new int[nodeCount];
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        seen[start] = true;
        double total = 0;
        while (head < tail) {
            int current = queue[head++];
            if (nodeTypes[current] == NodeType.CUSTOMER) {
                total += demand[current];
            }
            for (int e = 0; e < linkCount; e++) {
                if (linkSource[e] != current) {
                    continue;
                }
                int next = linkTarget[e];
                if (next >= 0 && !seen[next]) {
                    seen[next] = true;
                    queue[tail++] = next;
                }
            }
        }
        return total;
    }

    /** The indexed, single-commodity view of one snapshot. */
    public static SimulationNetwork of(NetworkGraph graph) {
        return new SimulationNetwork(graph);
    }

    /**
     * Collapses a node's per-product rows into the four aggregate quantities.
     *
     * <p>The two weighted means are the only interesting part. A node's holding cost per unit-period
     * is weighted by the stock each product contributes ({@code initialInventory + safetyStock}),
     * because that is the mix the aggregate inventory is actually made of; its shortage price is
     * weighted by demand, because that is the mix a shortfall actually falls on. Where a weight is
     * zero across the board the plain mean is used — a node holding no stock still has a holding
     * rate if it ever acquires any, and averaging over nothing would report 0 and make carrying
     * inventory free.
     */
    private void aggregateProducts(int i, GraphNode node) {
        List<GraphNodeProduct> products = node.products();
        if (products.isEmpty()) {
            return;
        }
        double demandSum = 0;
        double inventorySum = 0;
        double safetySum = 0;
        double stockWeighted = 0;
        double demandWeighted = 0;
        double holdingPlain = 0;
        double valuePlain = 0;
        for (GraphNodeProduct product : products) {
            double productDemand = Math.max(0, product.demandPerPeriod());
            double stock = Math.max(0, product.initialInventory()) + Math.max(0, product.safetyStock());
            demandSum += productDemand;
            inventorySum += Math.max(0, product.initialInventory());
            safetySum += Math.max(0, product.safetyStock());
            stockWeighted += product.holdingCostPerPeriod() * stock;
            demandWeighted += product.unitValue() * productDemand;
            holdingPlain += product.holdingCostPerPeriod();
            valuePlain += product.unitValue();
        }
        demand[i] = demandSum;
        initialInventory[i] = inventorySum;
        safetyStock[i] = safetySum;
        double stockWeight = inventorySum + safetySum;
        holdingCost[i] = stockWeight > 0 ? stockWeighted / stockWeight : holdingPlain / products.size();
        shortagePenalty[i] = demandSum > 0 ? demandWeighted / demandSum : valuePlain / products.size();
    }

    /** SUPPLIER or PLANT — the "supply-side" of {@code NetworkChecks}. */
    private static boolean isSupplySide(NodeType type) {
        return type == NodeType.SUPPLIER || type == NodeType.PLANT;
    }

    private static double clampProbability(double probability) {
        return Math.min(1, Math.max(0, probability));
    }

    // ------------------------------------------------------------------------ readers

    public long networkId() {
        return networkId;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int linkCount() {
        return linkCount;
    }

    public long nodeId(int i) {
        return nodeIds[i];
    }

    public String nodeName(int i) {
        return nodeNames[i];
    }

    public NodeType nodeType(int i) {
        return nodeTypes[i];
    }

    /** Nominal per-period throughput; infinite where the network declares no ceiling. */
    public double nodeCapacity(int i) {
        return nodeCapacity[i];
    }

    /** Dwell before material entering this node can leave it, in whole periods. */
    public long processingPeriods(int i) {
        return processingPeriods[i];
    }

    public double varCost(int i) {
        return varCost[i];
    }

    public double nodeFailureProb(int i) {
        return nodeFailureProb[i];
    }

    /** Per-period demand at this node; zero anywhere but a CUSTOMER. */
    public double demand(int i) {
        return demand[i];
    }

    public double initialInventory(int i) {
        return initialInventory[i];
    }

    public double safetyStock(int i) {
        return safetyStock[i];
    }

    /**
     * The order-up-to level node {@code i} aims at — safety stock plus one period of the demand it
     * covers. See {@link #computeReplenishTargets()} for why the second term exists.
     */
    public double replenishTarget(int i) {
        return replenishTarget[i];
    }

    public double holdingCost(int i) {
        return holdingCost[i];
    }

    /** What one unit of unmet demand at this node costs — the penalty arc's price. */
    public double shortagePenalty(int i) {
        return shortagePenalty[i];
    }

    /** Whether material enters the model here. Fixed on the intact graph; see the class note. */
    public boolean isSupplyOrigin(int i) {
        return supplyOrigin[i];
    }

    /** Indices of the CUSTOMER nodes, in snapshot order. */
    public int[] customers() {
        return customers;
    }

    public long linkId(int e) {
        return linkIds[e];
    }

    /** Source node index, or -1 for an arc whose endpoint is missing from the snapshot. */
    public int linkSource(int e) {
        return linkSource[e];
    }

    public int linkTarget(int e) {
        return linkTarget[e];
    }

    public double linkCapacity(int e) {
        return linkCapacity[e];
    }

    /** Transit time in whole periods; 0 means same-period arrival. */
    public long leadTimePeriods(int e) {
        return leadTimePeriods[e];
    }

    public double linkUnitCost(int e) {
        return linkUnitCost[e];
    }

    public double linkFailureProb(int e) {
        return linkFailureProb[e];
    }

    /** Whether both endpoints exist — an arc that fails this is skipped by every consumer. */
    public boolean linkIsUsable(int e) {
        return linkSource[e] >= 0 && linkTarget[e] >= 0;
    }

    public double totalDemandPerPeriod() {
        return totalDemandPerPeriod;
    }

    public double totalSafetyStock() {
        return totalSafetyStock;
    }

    /**
     * Per-period cost of operating every node in the network.
     *
     * <p>Charged in full every period, whatever the node ships and whatever an event has done to it:
     * {@code fixed_cost} is the cost of <em>having</em> the node, and an idle or damaged
     * plant is not a cheaper plant. The consequence worth stating is that a disruption never reduces
     * fixed cost, so {@code DISRUPTION_COST_DELTA} measures lost service and re-routing rather than
     * an accounting artefact — and that the topology levers, which open and close nodes,
     * move {@code TOTAL_COST} through exactly this term.
     */
    public double totalFixedCostPerPeriod() {
        return totalFixedCost;
    }

    /** How many periods ahead the pipeline has to hold shipments — one past the longest delay. */
    public int pipelineDepth() {
        return pipelineDepth;
    }

    /** Index of a node by its persistent id, or null — used to apply a resolved disruption target. */
    public Integer indexOfNode(long nodeId) {
        return indexByNodeId.get(nodeId);
    }

    /** Index of a link by its persistent id, or null. */
    public Integer indexOfLink(long linkId) {
        return indexByLinkId.get(linkId);
    }
}
