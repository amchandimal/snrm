package com.snrm.network;

import com.snrm.common.TimeBasis;
import org.jgrapht.Graph;
import org.jgrapht.graph.AsUnmodifiableGraph;
import org.jgrapht.graph.DirectedWeightedPseudograph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * An immutable in-memory picture of one network, and the only thing the engines are allowed to see.
 *
 * <p>The metric engine, the simulation engine and the Phase 2 configuration engine
 * depend on this class and on nothing else from persistence — no JPA entity, no
 * repository, no web type. That is what makes a Monte Carlo run over hundreds of replications on
 * virtual threads safe without a lock, and it is what will let Phase 2 evaluate thousands
 * of candidate configurations without a database round trip per evaluation.
 *
 * <p><strong>Units stop here.</strong> The snapshot carries a {@link TimeBasis} —
 * the network's period length and rounding policy — and every time-valued attribute beneath it has
 * already been converted through that basis: durations are whole period counts, rates are per-period
 * quantities. Nothing downstream imports {@code TimeUnit}, {@code DurationAmount} or {@code Rate},
 * and nothing downstream can disagree with anything else about what "a period" means. The basis is
 * exposed rather than hidden because results have to be reported back in human terms — a recovery
 * time of 9 periods is 9 days on one network and 9 hours on another — and because comparing
 * variants means showing that they share a clock.
 *
 * <p><strong>Read-only by construction.</strong> The collections are unmodifiable copies of
 * unmodifiable records; there are no setters and no way to reach an entity from here. A snapshot is
 * taken once, at the start of a computation, and is never refreshed — a run evaluates the network as
 * it stood when the run was accepted, which is the same guarantee the immutability rule
 * makes at the database level.
 *
 * <p><strong>It is a JGraphT graph.</strong> {@link #jgrapht()} is the same structure the
 * lists and adjacency indexes above describe, expressed as an unmodifiable directed weighted graph
 * so that the library's shortest-path, connectivity and maximum-flow implementations can be used
 * directly rather than reimplemented. It is built eagerly in the constructor rather than
 * lazily, because a lazily-initialised field would need synchronisation to keep the promise this
 * class makes to the parallel replications, and building it costs one pass over the
 * links.
 */
public final class NetworkGraph {

    private final long networkId;
    private final String networkName;
    private final int networkVersion;
    private final TimeBasis timeBasis;
    private final int horizonPeriods;
    private final List<GraphNode> nodes;
    private final List<GraphLink> links;
    private final Map<Long, GraphNode> nodesById;
    private final Map<Long, List<GraphLink>> outboundBySource;
    private final Map<Long, List<GraphLink>> inboundByTarget;
    private final Graph<Long, GraphLink> jgrapht;

    /**
     * Built by {@link NetworkGraphFactory} and nowhere else — the arguments are already converted,
     * and there is no other way to be sure of that.
     */
    NetworkGraph(long networkId, String networkName, int networkVersion, TimeBasis timeBasis,
            int horizonPeriods, List<GraphNode> nodes, List<GraphLink> links) {
        this.networkId = networkId;
        this.networkName = networkName;
        this.networkVersion = networkVersion;
        this.timeBasis = timeBasis;
        this.horizonPeriods = horizonPeriods;
        this.nodes = List.copyOf(nodes);
        this.links = List.copyOf(links);

        Map<Long, GraphNode> byId = new LinkedHashMap<>(this.nodes.size());
        for (GraphNode node : this.nodes) {
            byId.put(node.id(), node);
        }
        this.nodesById = Collections.unmodifiableMap(byId);

        this.outboundBySource = index(this.links, GraphLink::sourceNodeId);
        this.inboundByTarget = index(this.links, GraphLink::targetNodeId);
        this.jgrapht = buildJGraphT(this.nodes, this.links);
    }

    /**
     * The same structure as a JGraphT directed weighted graph.
     *
     * <p><strong>Vertices are node ids and edges are {@link GraphLink} records.</strong> Neither is
     * a wrapper: an algorithm's answer comes back in the same identifiers a {@code MetricValue}
     * has to be attributed to, so nothing has to be translated on the way out.
     *
     * <p><strong>The weight is the arc's per-period capacity</strong> — {@link
     * GraphLink#capacityPerPeriod()}, already converted onto the network's clock — and is
     * {@link Double#POSITIVE_INFINITY} for an unconstrained arc, which is the honest reading of a
     * null capacity. Weight is therefore a <em>throughput</em>, not a distance: it is what the
     * maximum-flow computation behind {@code NODE_CRITICALITY} consumes. Anything that wants a
     * distance must say which one it means, which is why {@code AVG_PATH} counts hops explicitly
     * rather than summing these weights.
     *
     * <p>A {@code DirectedWeightedPseudograph} rather than a simple graph: {@code uq_link} and
     * {@code ck_link_no_self_loop} already forbid parallel arcs and self-loops, so the tolerant type
     * costs nothing, and a snapshot is a picture of what the database holds — it must not be the
     * thing that throws on data the database accepted.
     *
     * @return an unmodifiable view; a mutation attempt throws rather than corrupting a snapshot
     *         shared across replications
     */
    public Graph<Long, GraphLink> jgrapht() {
        return jgrapht;
    }

    /** The network this is a picture of; every persisted result carries the same id. */
    public long networkId() {
        return networkId;
    }

    public String networkName() {
        return networkName;
    }

    /** Variant generation within {@code (project, name)} — what the comparison view labels. */
    public int networkVersion() {
        return networkVersion;
    }

    /**
     * The clock every number below was converted through.
     *
     * <p>Engines use it to restate a period count in human terms on the way out, not to convert on
     * the way in — that has already happened.
     */
    public TimeBasis timeBasis() {
        return timeBasis;
    }

    /** How many periods a run over this network covers. */
    public int horizonPeriods() {
        return horizonPeriods;
    }

    public List<GraphNode> nodes() {
        return nodes;
    }

    public List<GraphLink> links() {
        return links;
    }

    /** @return the node with this id, or null — ids come from the same snapshot, so null is a bug */
    public GraphNode node(long nodeId) {
        return nodesById.get(nodeId);
    }

    /** Arcs leaving a node, in insertion order; empty for a sink. */
    public List<GraphLink> outbound(long nodeId) {
        return outboundBySource.getOrDefault(nodeId, List.of());
    }

    /** Arcs entering a node, in insertion order; empty for a source. */
    public List<GraphLink> inbound(long nodeId) {
        return inboundByTarget.getOrDefault(nodeId, List.of());
    }

    /**
     * One pass over the links, wrapped so nothing downstream can add to it.
     *
     * <p>An arc whose endpoint is missing from the node list is skipped rather than allowed to
     * throw. The foreign keys make it impossible, and both collections are read in the same
     * transaction — but "impossible" is not a reason to turn a snapshot into a failure path.
     */
    private static Graph<Long, GraphLink> buildJGraphT(List<GraphNode> nodes,
            List<GraphLink> links) {
        DirectedWeightedPseudograph<Long, GraphLink> graph =
                new DirectedWeightedPseudograph<>(GraphLink.class);
        for (GraphNode node : nodes) {
            graph.addVertex(node.id());
        }
        for (GraphLink link : links) {
            if (!graph.containsVertex(link.sourceNodeId())
                    || !graph.containsVertex(link.targetNodeId())) {
                continue;
            }
            if (graph.addEdge(link.sourceNodeId(), link.targetNodeId(), link)) {
                graph.setEdgeWeight(link, link.capacityPerPeriod() == null
                        ? Double.POSITIVE_INFINITY
                        : link.capacityPerPeriod());
            }
        }
        return new AsUnmodifiableGraph<>(graph);
    }

    private static Map<Long, List<GraphLink>> index(List<GraphLink> links,
            ToLongFunction<GraphLink> key) {
        Map<Long, List<GraphLink>> index = new LinkedHashMap<>();
        for (GraphLink link : links) {
            index.computeIfAbsent(key.applyAsLong(link), id -> new ArrayList<>()).add(link);
        }
        index.replaceAll((id, arcs) -> Collections.unmodifiableList(arcs));
        return Collections.unmodifiableMap(index);
    }

    @Override
    public String toString() {
        return "NetworkGraph[network=%d v%d, %d nodes, %d links, %s, horizon %d periods]"
                .formatted(networkId, networkVersion, nodes.size(), links.size(), timeBasis,
                        horizonPeriods);
    }
}
