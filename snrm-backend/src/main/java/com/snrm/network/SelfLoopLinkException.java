package com.snrm.network;

import com.snrm.common.DomainException;

/**
 * A link whose source and target are the same node.
 *
 * <p>422 rather than 409: no arrangement of the data would make this request valid, so there is
 * nothing for the caller to resolve — the request itself is wrong.
 *
 * <p>Rejected three times over by design. The editor blocks the gesture so the user never draws it
 * import rejects the row with a line number, and
 * {@code ck_link_no_self_loop} in {@code V2__domain.sql} is the backstop. This exception is the
 * middle layer, and the only one an API client sees: a node cannot supply itself, and a self-loop
 * in the flow graph would let the min-cost-flow allocation circulate material at zero
 * cost.
 */
public class SelfLoopLinkException extends DomainException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "LINK_SELF_LOOP";

    private final long nodeId;

    public SelfLoopLinkException(long nodeId) {
        super(("A link cannot start and end at node %d. Self-loops are rejected: a node cannot "
                + "supply itself, and the flow allocation has no meaning for one.")
                .formatted(nodeId));
        this.nodeId = nodeId;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The node named as both source and target. */
    public long getNodeId() {
        return nodeId;
    }
}
