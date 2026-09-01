package com.snrm.simulation;

/**
 * One arc of the period's flow network.
 *
 * <p>A mutable-free identity object rather than JGraphT's {@code DefaultWeightedEdge}, because the
 * solution comes back keyed by edge and every arc has to be read back as <em>something the inventory
 * balance understands</em>: this arc was a node's throughput, that one was a lead-time shipment, this
 * other one was demand being met. {@link #kind} and {@link #index} are how the solution is decoded
 * without a second map from edges to meanings.
 *
 * <p>Identity equality, deliberately — two arcs with the same kind and index cannot exist, and a
 * value-equality edge would collapse parallel arcs inside the graph.
 *
 * @param kind  what this arc represents
 * @param index the node or link index it belongs to, or -1 for the two network-wide penalty arcs
 */
record FlowArc(FlowArc.Kind kind, int index) {

    /**
     * The seven roles an arc can play. Each is read back by {@code FlowAllocator} into one of the
     * quantities of {@link FlowSolution}.
     */
    enum Kind {

        /** {@code S → i_in}: material entering the model at a supply origin. */
        PRODUCTION,

        /** {@code i_in → i_mid}: the node's throughput ceiling, and where {@code varCost} is paid. */
        NODE_THROUGHPUT,

        /** {@code i_mid → i_out}: material passing straight through, available to leave this period. */
        NODE_PASS,

        /** {@code i_mid → i_hold}: material stopping at this node — immediately, or after its dwell. */
        NODE_HOLD,

        /** {@code S → i_out}: on-hand inventory dispatched, bypassing the node's production capacity. */
        STOCK,

        /** A real link. Lead 0 lands at {@code v_in}; lead ≥ 1 lands at {@code v_hold}, delayed. */
        LINK,

        /** {@code c_out → T_D}: demand met at a customer. */
        DEMAND,

        /** {@code S → T_D}: the penalty arc. Its flow is the unserved demand. */
        DEMAND_PENALTY,

        /** {@code i_hold → T_R}: material counted against this node's safety-stock shortfall. */
        REPLENISH,

        /** {@code S → T_R}: the replenishment shortfall nobody filled. */
        REPLENISH_PENALTY
    }
}
