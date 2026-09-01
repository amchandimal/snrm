package com.snrm.network;

import com.snrm.common.ConflictException;

/**
 * A second link between an ordered pair of nodes that already has one ({@code uq_link}).
 *
 * <p>409 rather than 422: the request is well formed and the caller has an obvious remedy — edit
 * the link that exists, whose id is carried on {@link #getExistingLinkId()} so a client can offer
 * it directly.
 *
 * <p>The pair is ordered, so {@code A -> B} and {@code B -> A} are different links and both may
 * exist; only a repeat of the same direction is refused. Two parallel arcs between the same nodes
 * would be indistinguishable in the min-cost-flow allocation and would double-count in
 * the topological metrics — differing capacity or lead time belongs on one arc, not two.
 */
public class DuplicateLinkException extends ConflictException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "LINK_DUPLICATE";

    private final long existingLinkId;

    public DuplicateLinkException(long networkId, long sourceNodeId, long targetNodeId,
            long existingLinkId) {
        super(("Network %d already has a link from node %d to node %d (link %d). Edit that link "
                + "rather than adding a parallel one.")
                .formatted(networkId, sourceNodeId, targetNodeId, existingLinkId));
        this.existingLinkId = existingLinkId;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The link that already connects the pair — what the caller should edit instead. */
    public long getExistingLinkId() {
        return existingLinkId;
    }
}
