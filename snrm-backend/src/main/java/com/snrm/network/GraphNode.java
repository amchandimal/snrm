package com.snrm.network;

import java.util.List;

/**
 * A node inside a {@link NetworkGraph} snapshot — the form the engines see.
 *
 * <p>Every time-valued attribute has already been converted against the network's
 * {@link com.snrm.common.TimeBasis}: {@code capacityPerPeriod} is throughput in one period and
 * {@code processingPeriods} a whole number of steps. Neither carries a unit, because past this
 * boundary there are no units — a metric calculator or a simulation step that had to ask what unit a
 * capacity was in would be a design bug.
 *
 * @param id                the node's persistent id, so results can be attributed back to a row
 * @param name              its name, for labelling metric results
 * @param type              echelon: SUPPLIER, PLANT, DC or CUSTOMER
 * @param capacityPerPeriod throughput ceiling in one period, or null for unconstrained
 * @param processingPeriods dwell before material can leave, in whole periods; 0 means no dwell
 * @param fixedCost         per-period fixed cost of operating the node
 * @param varCost           per-unit variable cost
 * @param failureProb       independent per-period failure probability in [0,1]
 * @param region            geographic tag REGION-scoped disruptions resolve through
 * @param products          per-product parameters, already converted
 */
public record GraphNode(
        long id,
        String name,
        NodeType type,
        Double capacityPerPeriod,
        long processingPeriods,
        double fixedCost,
        double varCost,
        double failureProb,
        String region,
        List<GraphNodeProduct> products) {

    /** Defensive copy, so a snapshot handed to parallel replications cannot be edited. */
    public GraphNode {
        products = products == null ? List.of() : List.copyOf(products);
    }
}
