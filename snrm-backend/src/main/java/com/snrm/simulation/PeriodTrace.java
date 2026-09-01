package com.snrm.simulation;

/**
 * Step ⑤ of the per-period loop: what happened in one period of one replication.
 *
 * <blockquote>"Records the period trace (served/unserved demand, costs, inventory levels)."
 * </blockquote>
 *
 * <p>The atom every simulated metric is computed from, and the row
 * {@code RUN_TIMESERIES} averages across replications. Costs are kept as five separate components
 * rather than one total because {@code DISRUPTION_COST_DELTA} is only interpretable when a reader
 * can see <em>which</em> component moved — a disruption that raised transport cost by re-routing is
 * a different finding from one that raised shortage cost by failing to serve, and a single number
 * cannot tell them apart.
 *
 * @param period            0-based index within the horizon
 * @param totalDemand       demand realised at customers this period, after any noise
 * @param servedDemand      how much of it was met
 * @param endingInventory   on-hand stock across the whole network at the end of the period
 * @param inPipeline        material in transit or in processing dwell at the end of the period —
 *                          shipped and paid for but not yet anywhere
 * @param fixedCost         cost of operating the network's nodes for one period
 * @param variableCost      {@code Σ throughput × varCost}
 * @param transportCost     {@code Σ link flow × unitCost}
 * @param holdingCost       {@code Σ ending inventory × holding rate}
 * @param shortageCost      unmet demand priced at what the customer's product is worth
 * @param disrupted         whether any scenario event was in flight this period
 */
public record PeriodTrace(
        int period,
        double totalDemand,
        double servedDemand,
        double endingInventory,
        double inPipeline,
        double fixedCost,
        double variableCost,
        double transportCost,
        double holdingCost,
        double shortageCost,
        boolean disrupted) {

    /** Demand that went unmet — the flow the penalty arc absorbed. */
    public double unservedDemand() {
        return Math.max(0, totalDemand - servedDemand);
    }

    /**
     * Served over total demand in this period.
     *
     * <p><strong>A period with no demand has no fill rate</strong>, and returns 1 rather than 0/0.
     * One is the reading that does not punish a network for a period nobody asked anything of it;
     * the metrics that would be distorted by it — {@code MIN_FILL_RATE} above all — exclude such
     * periods outright rather than relying on this value.
     */
    public double fillRate() {
        return totalDemand <= 0 ? 1 : servedDemand / totalDemand;
    }

    /** Whether this period had any demand at all — the guard the service metrics apply. */
    public boolean hasDemand() {
        return totalDemand > 0;
    }

    /** All five components. What {@code RUN_TIMESERIES.cost} stores and {@code TOTAL_COST} sums. */
    public double totalCost() {
        return fixedCost + variableCost + transportCost + holdingCost + shortageCost;
    }
}
