package com.snrm.simulation;

import com.snrm.common.DomainException;

/**
 * The network has no demand to serve, so every simulated metric would be vacuous.
 *
 * <p>A run against a network whose customers demand nothing completes perfectly happily and reports
 * a fill rate of 1, a service level of 1, a loss area of 0 and a resilience index of 1 — for every
 * scenario, every disruption and every configuration variant. Those numbers are arithmetically
 * correct and completely uninformative, and worse, they are indistinguishable from the numbers a
 * genuinely resilient network produces. A comparison view putting such a run beside a real
 * one would rank it first.
 *
 * <p>So it is refused at submission rather than discovered in the results. The likely causes are
 * concrete and the message names them: the network has no {@code CUSTOMER} nodes at all, or it has
 * customers with no {@code node_product} row, or the demand was entered on a plant — demand
 * applies to customers, and the importer already warns about it.
 *
 * <p>The topological suite has no equivalent refusal and should not: {@code DENSITY} and
 * {@code AVG_PATH} are properties of the structure and mean the same thing with or without demand.
 * This is a simulation-side rule.
 */
public class NoDemandException extends DomainException {

    private final long networkId;

    public NoDemandException(long networkId, int customerCount) {
        super(("Network %d has no demand to simulate (%d CUSTOMER node%s, total demand 0). Every "
                + "simulated metric would report perfect service for every scenario, "
                + "which is indistinguishable from a resilient network. Add demand on the customer "
                + "nodes — `demand_value` applies to CUSTOMER rows — or check that it was "
                + "not entered on a plant.")
                .formatted(networkId, customerCount, customerCount == 1 ? "" : "s"));
        this.networkId = networkId;
    }

    @Override
    public String code() {
        return "NETWORK_HAS_NO_DEMAND";
    }

    public long getNetworkId() {
        return networkId;
    }
}
