package com.snrm.network;

import com.snrm.common.DomainException;

/**
 * A request that names a node belonging to a different network from the one it is operating on
 * ({@code LINK} is a child of exactly one {@code NETWORK}).
 *
 * <p>The case that matters is link creation. Node ids are globally unique, so
 * {@code POST /networks/7/links} with a target node from network 9 is a plausible client mistake —
 * a stale id, a copy-paste between two editor tabs — and nothing in the schema would catch it:
 * {@code link.network_id} would say 7 while {@code fk_link_target} happily resolved a node in 9.
 * The result would be a network whose graph snapshot contains an arc to a node it does not own,
 * which is a corrupt input to every metric and simulation that follows.
 *
 * <p>The same check guards the bulk canvas endpoints, where a batch is rejected whole
 * rather than partially applied.
 *
 * <p>422: the ids are real, but their combination is not something this network can express.
 */
public class CrossNetworkReferenceException extends DomainException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "LINK_CROSS_NETWORK";

    private final long networkId;
    private final long foreignId;

    /**
     * @param networkId the network the request addressed
     * @param role      what the foreign id was being used as — {@code "source node"},
     *                  {@code "target node"}, {@code "node"}
     * @param foreignId the id that belongs elsewhere
     * @param actualNetworkId the network it actually belongs to
     */
    public CrossNetworkReferenceException(long networkId, String role, long foreignId,
            long actualNetworkId) {
        super("The %s %d belongs to network %d, not to network %d."
                .formatted(role, foreignId, actualNetworkId, networkId));
        this.networkId = networkId;
        this.foreignId = foreignId;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The network the request addressed. */
    public long getNetworkId() {
        return networkId;
    }

    /** The id that belongs to another network. */
    public long getForeignId() {
        return foreignId;
    }
}
