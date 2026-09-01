package com.snrm.network;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Region tags as a network's own vocabulary, and what each one resolves to.
 *
 * <p>{@code node.region} is a free-text tag — typed in the property panel, or imported from the
 * {@code region} column — and a {@code REGION}-scoped disruption event names one of them
 * to express a correlated geographic disruption. That indirection is what makes the feature worth
 * having and also what makes it easy to get wrong: nothing about the event says which nodes it hits,
 * and a tag that matches none of them produces a scenario that runs, completes, and shows a network
 * shrugging off a disruption it never received.
 *
 * <p>So the resolution is exposed rather than left implicit. {@link #catalogue} lists the tags that
 * are in use, which is what the timeline's row picker offers; {@link #resolve} answers what one of
 * them covers, which is the preview shown beside a REGION bar before it is saved. Both run the query
 * the simulation engine will run — {@code region = ?} within one network — so the preview and the
 * run cannot disagree.
 *
 * <p>Lives in {@code network} rather than in {@code scenario} because the question is entirely about
 * a network's nodes: it has no scenario, no event and no timing in it, and answering it here lets
 * {@link NodeMapper} be reused instead of copied. {@code DisruptionScenarioService} asks
 * {@link NodeRepository} the same question directly when it refuses an empty region.
 */
@Service
@Transactional(readOnly = true)
public class RegionService {

    private final NetworkLookup lookup;
    private final NodeRepository nodes;
    private final NodeMapper mapper;

    RegionService(NetworkLookup lookup, NodeRepository nodes, NodeMapper mapper) {
        this.lookup = lookup;
        this.nodes = nodes;
        this.mapper = mapper;
    }

    /**
     * Every region tag in use in a network, alphabetically, each with its node count.
     *
     * <p>Derived from the nodes rather than stored, so it cannot go stale: retag the last node of a
     * region and the region stops existing, which is exactly what a picker should show. The cost is
     * one count per distinct tag — a handful of queries over a column that is indexed by
     * {@code ix_node_network_region}, on a list a researcher reads.
     */
    public List<RegionDto> catalogue(long networkId, long ownerId) {
        lookup.requireNetwork(networkId, ownerId);
        List<RegionDto> catalogue = new ArrayList<>();
        for (String region : nodes.findDistinctRegions(networkId)) {
            catalogue.add(new RegionDto(region, nodes.countByNetworkIdAndRegion(networkId, region)));
        }
        return catalogue;
    }

    /**
     * The nodes of a network carrying one tag — what a {@code REGION} event will strike.
     *
     * <p>An unknown tag is an empty list, not a 404: the tag is a value the caller supplied, not a
     * resource, and "no node here carries that" is the answer the builder needs to show. Only the
     * network can be missing.
     *
     * <p>Ordered by id, which is creation order — stable across calls, so a preview does not
     * reshuffle while the user reads it, and it does not depend on a name the user may be editing.
     */
    public RegionNodesDto resolve(long networkId, String region, long ownerId) {
        lookup.requireNetwork(networkId, ownerId);
        String tag = region == null ? "" : region.trim();

        List<Node> matched = tag.isEmpty()
                ? List.of()
                : nodes.findByNetworkIdAndRegion(networkId, tag).stream()
                        .sorted(Comparator.comparing(Node::getId))
                        .toList();

        return new RegionNodesDto(networkId, tag, matched.size(), mapper.toDtoList(matched));
    }
}
