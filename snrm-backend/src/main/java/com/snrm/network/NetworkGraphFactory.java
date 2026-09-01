package com.snrm.network;

import com.snrm.common.TimeBasis;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the immutable {@link NetworkGraph} snapshot the engines run on.
 *
 * <p>This is the seam between the persistence half of the system and the computational half. On one
 * side: JPA entities, lazy associations, a transaction, and durations stated in whatever unit the
 * researcher found natural. On the other: records of primitives, safe to hand to a hundred virtual
 * threads at once, counting periods. Everything that has to happen in between happens here, once per
 * computation, and nowhere else.
 *
 * <p><strong>What comes out is a JGraphT graph.</strong> The snapshot exposes the
 * structure as an unmodifiable directed weighted graph — {@link NetworkGraph#jgrapht()} — so the
 * metric calculators run library algorithms over node ids and {@link GraphLink} records
 * rather than over entities. Nothing beyond this class ever sees a {@link Node}, a {@link Link} or a
 * repository, which is the isolation the layering depends on.
 *
 * <p><strong>The unit conversion happens here and only here.</strong> A
 * {@link TimeBasis} is built from the network's period length and rounding policy, every duration
 * beneath it becomes a whole number of periods and every rate a per-period quantity, and the
 * {@code TimeUnit} enum does not cross the boundary. That is what makes it true that no
 * engine code changes when a unit changes: there is no engine code that could.
 *
 * <p><strong>Minimal by design, for now.</strong> This is the version FR-13 needs — the whole
 * structure, correctly converted — and deliberately not more. It reads through four repositories in
 * one read-only transaction, which is right for a research tool at the 1,000-node scale of FR-04 and
 * will want revisiting when the simulation engine starts taking snapshots inside a job
 * loop: a projection query per collection, and possibly a cache keyed by network id, since a frozen
 * network cannot change underneath one. Neither belongs here before there is a caller to
 * measure. Ownership is not checked either — callers resolve the network through
 * {@link NetworkLookup} first, the way every service in this package does.
 */
@Component
public class NetworkGraphFactory {

    private final NetworkRepository networks;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final NodeProductRepository nodeProducts;

    NetworkGraphFactory(NetworkRepository networks, NodeRepository nodes, LinkRepository links,
            NodeProductRepository nodeProducts) {
        this.networks = networks;
        this.nodes = nodes;
        this.links = links;
        this.nodeProducts = nodeProducts;
    }

    /**
     * The network as the engines see it: whole periods, per-period rates, no units.
     *
     * @throws EntityNotFoundException if no such network exists
     */
    @Transactional(readOnly = true)
    public NetworkGraph snapshot(long networkId) {
        Network network = networks.findById(networkId)
                .orElseThrow(() -> new EntityNotFoundException("No network with id " + networkId));
        TimeBasis basis = TimeBasis.of(network.getPeriodLength(), network.getRoundingPolicy());

        Map<Long, List<GraphNodeProduct>> productsByNode = new HashMap<>();
        for (NodeProduct nodeProduct : nodeProducts.findByNetworkId(networkId)) {
            productsByNode
                    .computeIfAbsent(nodeProduct.getId().getNodeId(), id -> new ArrayList<>())
                    .add(convert(nodeProduct, basis));
        }

        List<Node> found = nodes.findByNetworkId(networkId);
        List<GraphNode> graphNodes = new ArrayList<>(found.size());
        for (Node node : found) {
            graphNodes.add(convert(node, basis, productsByNode.getOrDefault(node.getId(), List.of())));
        }

        List<Link> arcs = links.findByNetworkId(networkId);
        List<GraphLink> graphLinks = new ArrayList<>(arcs.size());
        for (Link link : arcs) {
            graphLinks.add(convert(link, basis));
        }

        return new NetworkGraph(networkId, network.getName(), network.getVersion(), basis,
                network.getHorizonPeriods(), graphNodes, graphLinks);
    }

    private static GraphNode convert(Node node, TimeBasis basis, List<GraphNodeProduct> products) {
        return new GraphNode(node.getId(), node.getName(), node.getType(),
                basis.toPerPeriod(node.getCapacity()),
                basis.toPeriods(node.getProcessingTime()),
                node.getFixedCost(), node.getVarCost(), node.getFailureProb(), node.getRegion(),
                products);
    }

    private static GraphLink convert(Link link, TimeBasis basis) {
        return new GraphLink(link.getId(), link.getSourceNode().getId(),
                link.getTargetNode().getId(),
                basis.toPeriods(link.getLeadTime()),
                basis.toPerPeriod(link.getCapacity()),
                link.getUnitCost(), link.getFailureProb());
    }

    private static GraphNodeProduct convert(NodeProduct nodeProduct, TimeBasis basis) {
        // Demand and holding cost are rates and rescale exactly; inventory, safety stock and the
        // product's unit value are levels or prices with no time dimension and cross unchanged
        // The unit value is what the simulation engine prices unmet demand at, which is
        // what makes the penalty arc an economic quantity. A null demand cannot
        // occur — the column is NOT NULL — but toPerPeriod is null-safe, and treating "no demand
        // stated" as zero is the only reading that keeps the flow allocation solvable.
        Double demand = basis.toPerPeriod(nodeProduct.getDemand());
        Double holdingCost = basis.toPerPeriod(nodeProduct.getHoldingCost());
        return new GraphNodeProduct(nodeProduct.getProduct().getId(),
                nodeProduct.getProduct().getName(),
                demand == null ? 0 : demand,
                nodeProduct.getInitialInventory(), nodeProduct.getSafetyStock(),
                holdingCost == null ? 0 : holdingCost,
                nodeProduct.getProduct().getUnitValue());
    }
}
