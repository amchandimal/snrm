package com.snrm.simulation;

/**
 * What one period's minimum-cost flow decided.
 *
 * <p>Arrays indexed the way {@link SimulationNetwork} indexes: {@code [i]} is a node, {@code [e]} a
 * link. Every quantity is in units, already converted back from the fixed-point grid of
 * {@link Quantiser}.
 *
 * <p>The five node quantities are exactly the flows the inventory balance needs, and they close:
 *
 * <pre>
 *   onHand'(i) = onHand(i) − stockDrawn(i) + heldNow(i)
 * </pre>
 *
 * <p>with {@code heldLater(i)} and the lead-time link flows going into the pipeline instead of into
 * this period's balance. That identity is what {@code SimulationEngine} asserts rather than
 * recomputes, and what the property tests check.
 *
 * @param served         demand met at each node this period; zero anywhere but a CUSTOMER
 * @param throughput     flow through each node's capacity arc — production at a supply origin,
 *                       pass-through elsewhere. What {@code varCost} is charged on
 * @param stockDrawn     on-hand inventory dispatched from each node this period
 * @param heldNow        material that arrived at each node and stayed, available immediately
 *                       (nodes with no processing time)
 * @param heldLater      material that entered each node's processing dwell and will land as its
 *                       inventory after {@code processingPeriods}
 * @param linkFlow       flow on each link. Lead-time links land at their target through the pipeline
 * @param variableCost   {@code Σ throughput(i) × varCost(i)}
 * @param transportCost  {@code Σ linkFlow(e) × unitCost(e)}
 * @param totalServed    {@code Σ served}
 */
record FlowSolution(
        double[] served,
        double[] throughput,
        double[] stockDrawn,
        double[] heldNow,
        double[] heldLater,
        double[] linkFlow,
        double variableCost,
        double transportCost,
        double totalServed) {

    /** The solution of a period in which nothing could move — no demand and no replenishment need. */
    static FlowSolution empty(SimulationNetwork network) {
        return new FlowSolution(
                new double[network.nodeCount()],
                new double[network.nodeCount()],
                new double[network.nodeCount()],
                new double[network.nodeCount()],
                new double[network.nodeCount()],
                new double[network.linkCount()],
                0, 0, 0);
    }
}
