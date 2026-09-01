package com.snrm.network;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Link CRUD and the batched canvas writes.
 *
 * <p><strong>The three creation rules.</strong> {@link #create} rejects, in this order:
 *
 * <ol>
 *   <li><strong>self-loops</strong> — {@link SelfLoopLinkException}, 422. A node cannot supply
 *       itself, and a self-loop would let the min-cost-flow allocation circulate
 *       material at no cost;</li>
 *   <li><strong>cross-network references</strong> — {@link CrossNetworkReferenceException}, 422.
 *       Node ids are globally unique, so nothing in the schema would stop a link in network 7 from
 *       pointing at a node in network 9; the graph snapshot the engines read would then contain an
 *       arc to a node the network does not own;</li>
 *   <li><strong>duplicate pairs</strong> — {@link DuplicateLinkException}, 409. Parallel arcs are
 *       indistinguishable to the flow allocation and double-count in the topological metrics.
 *       The pair is ordered, so {@code A → B} and {@code B → A} may both exist.</li>
 * </ol>
 *
 * <p>The order matters for the message the caller gets: a request naming one node twice is a
 * self-loop first and foremost, whichever network that node lives in.
 *
 * <p>These are the middle of three layers. The editor blocks all three at the gesture level so the
 * user never draws them, import rejects them with a line number, and
 * {@code uq_link} plus {@code ck_link_no_self_loop} are the backstop. Only this layer produces a
 * message an API client can act on.
 *
 * <p><strong>Echelon direction is not enforced here.</strong> The editor blocks a link into a SUPPLIER
 * or out of a CUSTOMER at the gesture level and merely warns about lateral arcs such as DC → DC.
 * That is a UI affordance over a soft rule — the same thing is a network-level
 * <em>warning</em> for import — and a research tool that refused to model an unconventional
 * topology would be refusing the experiment. The API therefore accepts them.
 */
@Service
@Transactional
public class LinkService {

    private final LinkRepository links;
    private final NetworkMutationGuard guard;
    private final NetworkLookup lookup;
    private final LinkMapper mapper;

    LinkService(LinkRepository links, NetworkMutationGuard guard, NetworkLookup lookup,
            LinkMapper mapper) {
        this.links = links;
        this.guard = guard;
        this.lookup = lookup;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<LinkDto> listByNetwork(long networkId, long ownerId) {
        lookup.requireNetwork(networkId, ownerId);
        return mapper.toDtoList(links.findByNetworkId(networkId));
    }

    @Transactional(readOnly = true)
    public LinkDto get(long linkId, long ownerId) {
        return mapper.toDto(lookup.requireLink(linkId, ownerId));
    }

    /**
     * Connects two nodes of the same network — the edge-handle gesture.
     *
     * @throws SelfLoopLinkException          if source and target are the same node
     * @throws CrossNetworkReferenceException if either endpoint belongs to another network
     * @throws DuplicateLinkException         if the ordered pair is already connected
     */
    public LinkDto create(long networkId, long ownerId, LinkRequest request) {
        guard.assertMutable(networkId);
        Network network = lookup.requireNetwork(networkId, ownerId);

        long sourceNodeId = request.sourceNodeId();
        long targetNodeId = request.targetNodeId();
        if (sourceNodeId == targetNodeId) {
            throw new SelfLoopLinkException(sourceNodeId);
        }

        Node source = lookup.requireNodeIn(networkId, sourceNodeId, "source node");
        Node target = lookup.requireNodeIn(networkId, targetNodeId, "target node");

        links.findByNetworkIdAndSourceNodeIdAndTargetNodeId(networkId, sourceNodeId, targetNodeId)
                .ifPresent(existing -> {
                    throw new DuplicateLinkException(networkId, sourceNodeId, targetNodeId,
                            existing.getId());
                });

        Link link = new Link(network, source, target);
        mapper.applyAttributes(request, link);
        network.addLink(link);
        return mapper.toDto(links.save(link));
    }

    /** Full replacement of the attributes; the endpoints are fixed (see {@link LinkAttributesRequest}). */
    public LinkDto replace(long linkId, long ownerId, LinkAttributesRequest request) {
        Link link = lookup.requireLink(linkId, ownerId);
        guard.assertMutable(link.getNetwork().getId());
        mapper.replace(request, link);
        return mapper.toDto(link);
    }

    public void delete(long linkId, long ownerId) {
        Link link = lookup.requireLink(linkId, ownerId);
        guard.assertMutable(link.getNetwork().getId());
        links.delete(link);
    }

    // ------------------------------------------------------ batched canvas writes

    /**
     * Applies a batch of link attribute edits. Validated whole before anything is
     * written, for the reason {@link NodeService} gives.
     *
     * @return the edited links, in the order they were sent
     */
    public List<LinkDto> patchAll(long networkId, long ownerId, List<LinkPatch> patches) {
        guard.assertMutable(networkId);
        lookup.requireNetwork(networkId, ownerId);

        List<Long> ids = patches.stream().map(LinkPatch::linkId).toList();
        Map<Long, Link> byId = resolveAllIn(networkId, ids);

        List<LinkDto> edited = new ArrayList<>(patches.size());
        for (LinkPatch patch : patches) {
            Link link = byId.get(patch.linkId());
            mapper.patch(patch, link);
            edited.add(mapper.toDto(link));
        }
        return edited;
    }

    /**
     * Loads every id in one query and proves each one belongs to this network, before any of them
     * is written.
     *
     * @throws IllegalArgumentException       if an id repeats within the batch
     * @throws EntityNotFoundException        if an id matches no link
     * @throws CrossNetworkReferenceException if an id matches a link of another network
     */
    private Map<Long, Link> resolveAllIn(long networkId, List<Long> ids) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        if (distinct.size() != ids.size()) {
            throw new IllegalArgumentException(
                    "The same link id appears more than once in this batch; each link may be "
                            + "changed at most once per request.");
        }

        Map<Long, Link> byId = new HashMap<>(distinct.size());
        for (Link link : links.findAllById(distinct)) {
            byId.put(link.getId(), link);
        }
        for (Long id : distinct) {
            Link link = byId.get(id);
            if (link == null) {
                throw new EntityNotFoundException("No link with id " + id);
            }
            long actualNetworkId = link.getNetwork().getId();
            if (actualNetworkId != networkId) {
                throw new CrossNetworkReferenceException(networkId, "link", id, actualNetworkId);
            }
        }
        return byId;
    }
}
