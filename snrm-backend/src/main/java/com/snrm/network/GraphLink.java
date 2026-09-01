package com.snrm.network;

/**
 * A directed arc inside a {@link NetworkGraph} snapshot — the form the engines see.
 *
 * <p>{@code leadTimePeriods} is the discretised transit time: flow shipped in period <em>t</em>
 * arrives in <em>t + leadTimePeriods</em>, held as pipeline inventory in between. It is
 * whatever the network's rounding policy made of the declared duration, which is why a 6-hour lead
 * time on a network stepping in days arrives as 0 — and why {@code TimeValidationService} exists to
 * say so before a run is submitted rather than after.
 *
 * @param id                the arc's persistent id
 * @param sourceNodeId      upstream endpoint
 * @param targetNodeId      downstream endpoint
 * @param leadTimePeriods   transit time in whole periods; 0 means same-period arrival
 * @param capacityPerPeriod throughput ceiling in one period, or null for unconstrained
 * @param unitCost          cost per unit shipped
 * @param failureProb       independent per-period failure probability in [0,1]
 */
public record GraphLink(
        long id,
        long sourceNodeId,
        long targetNodeId,
        long leadTimePeriods,
        Double capacityPerPeriod,
        double unitCost,
        double failureProb) {
}
