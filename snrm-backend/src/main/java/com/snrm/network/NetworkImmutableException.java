package com.snrm.network;

import com.snrm.common.DomainException;

/**
 * Raised when a caller tries to change a network that a simulation run has already claimed.
 *
 * <p>The message names the remedy rather than just the refusal: fork a configuration variant and
 * edit that. The SPA's network editor branches on {@link #code()} to raise the fork prompt on the
 * user's first edit instead of surfacing an error.
 */
public class NetworkImmutableException extends DomainException {

    /** Problem-detail {@code code} the SPA branches on; part of the API contract. */
    public static final String CODE = "NETWORK_IMMUTABLE";

    private final long networkId;

    public NetworkImmutableException(long networkId) {
        super(("Network %d has been evaluated by a simulation run, so its structure is frozen: "
                + "metric results are only interpretable next to the exact network that "
                + "produced them. Fork a configuration variant "
                + "(POST /api/v1/networks/%d/clone) and edit the variant instead.")
                .formatted(networkId, networkId));
        this.networkId = networkId;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The frozen network — the one to fork from. */
    public long getNetworkId() {
        return networkId;
    }
}
