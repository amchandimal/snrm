package com.snrm.simulation;

import java.util.List;

/**
 * The per-element time series of one run, already averaged across replications — what
 * {@code run_node_timeseries} and {@code run_link_timeseries} store (FR-18).
 *
 * <p>Produced once, by {@link ElementAccumulator#mean()}, after every replication has been folded
 * in. Element-major and parallel-by-period: each element carries one array per quantity, indexed by
 * period, all of length {@link #horizonPeriods()}. That is both the shape the tables are keyed in
 * and the shape the API returns, so nothing between the engine and the wire has to transpose it
 * again.
 *
 * <p><strong>Means, not sums</strong>, exactly as {@code RUN_TIMESERIES} stores means: a
 * client comparing two runs with different replication counts must be comparing like with like.
 *
 * <p><strong>The three {@code baseline*} arrays are the undisrupted set</strong>, or —
 * on the baseline run of FR-17, where the pairing was skipped — a copy of the run's own disrupted
 * series. See {@link ElementAccumulator#mean()} and {@code V9__element_timeseries.sql} for why that
 * copy is definitionally true rather than a stand-in.
 *
 * <p>{@link ElementTrace#ABSENT} ({@code NaN}) still marks an undefined entry in
 * {@link NodeSeries#inboundLead()} and {@link LinkSeries#utilisation()}; the persistence edge turns
 * it into a SQL {@code NULL} and the API into a JSON {@code null}. Nothing downstream may read it as
 * zero.
 *
 * <p>A value object, like everything else the engine returns: no JPA type appears here or anywhere
 * upstream of {@link SimulationRunWriter}.
 *
 * @param horizonPeriods how many periods each array carries
 * @param nodes          one entry per node of the snapshot, in snapshot order
 * @param links          one entry per link of the snapshot, in snapshot order
 */
public record ElementSeries(int horizonPeriods, List<NodeSeries> nodes, List<LinkSeries> links) {

    public ElementSeries {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /**
     * One node's whole horizon.
     *
     * @param nodeId         the node's persistent id — the {@code node_id} half of the row key
     * @param onHand         end-of-period stock, after the period's arrivals and dispatches
     * @param inTransit      material on its way to this node at period end: shipments on inbound
     *                       links plus anything inside its own processing dwell
     * @param arrivals       what the pipeline delivered here this period, captured before the
     *                       arrival slot is cleared
     * @param served         demand met at this node; zero anywhere but a {@code CUSTOMER}
     * @param unserved       demand this node wanted and did not get; zero anywhere but a customer
     * @param throughput     flow across the node's own capacity arc — production at a supply origin,
     *                       pass-through elsewhere. Material shipped out of stock does not appear
     *                       here: stock enters the flow at the node's dispatch vertex and never
     *                       crosses the capacity arc
     * @param availability   the period's availability multiplier, events × random outages
     * @param inboundLead    dispatch-weighted transport lead, in periods, of everything sent toward
     *                       this node this period; {@link ElementTrace#ABSENT} when nothing was
     * @param baselineOnHand {@link #onHand} in the undisrupted baseline set
     * @param baselineServed {@link #served} in the undisrupted baseline set
     */
    public record NodeSeries(
            long nodeId,
            double[] onHand,
            double[] inTransit,
            double[] arrivals,
            double[] served,
            double[] unserved,
            double[] throughput,
            double[] availability,
            double[] inboundLead,
            double[] baselineOnHand,
            double[] baselineServed) {
    }

    /**
     * One link's whole horizon.
     *
     * @param linkId       the link's persistent id
     * @param flow         what was dispatched onto the arc this period. A lead-time arc lands its
     *                     flow at the target in a <em>later</em> period, which is the single thing a
     *                     playback view has to render correctly
     * @param utilisation  {@link #flow} over the capacity actually available this period;
     *                     {@link ElementTrace#ABSENT} where the arc declares no capacity, and where
     *                     an outage left it none
     * @param availability the period's availability multiplier for the arc
     * @param baselineFlow {@link #flow} in the undisrupted baseline set
     */
    public record LinkSeries(
            long linkId,
            double[] flow,
            double[] utilisation,
            double[] availability,
            double[] baselineFlow) {
    }
}
