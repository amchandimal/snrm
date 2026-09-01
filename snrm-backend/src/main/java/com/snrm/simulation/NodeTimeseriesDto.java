package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One node's whole horizon, as parallel arrays indexed by period (FR-18).
 *
 * <p><strong>Parallel arrays rather than a list of period objects</strong>, and that is a deliberate
 * shape rather than a compression. A playback view binds one array to one visual channel — stock
 * height, arrival pulse, availability tint — and re-binds it when the scrubber moves; a list of
 * {@code {period, onHand, inTransit, …}} objects would make every one of those a projection the
 * client has to compute, and would repeat the period index eleven times per period. Every array here
 * has the same length, and index {@code t} is period {@code t}, so the view needs no key lookup at
 * all.
 *
 * <p><strong>{@link #inboundLead()} carries nulls, and a null is a claim.</strong> It is the
 * dispatch-weighted transport lead of everything sent toward this node in that period; a period in
 * which nothing was dispatched toward it has no such mean. A 0 would say material arrived instantly,
 * which is a different and false statement — absent renders absent, never zero. It is the
 * one array here that is a list of boxed {@code Double}; the rest cannot be undefined.
 *
 * @param nodeId         the node's persistent id
 * @param name           the node's name, read from the (frozen) network — the label a view draws
 * @param onHand         end-of-period stock, mean across the disrupted replications
 * @param inTransit      material on its way here at period end: inbound shipments plus this node's
 *                       own processing dwell
 * @param arrivals       what the pipeline delivered here this period
 * @param served         demand met here; zero anywhere but a {@code CUSTOMER}
 * @param unserved       demand wanted and not got; zero anywhere but a customer
 * @param throughput     flow across this node's own capacity arc — production at a supply origin,
 *                       pass-through elsewhere. Material shipped out of stock is <em>not</em> here:
 *                       stock enters the flow at the node's dispatch vertex and never crosses the
 *                       capacity arc
 * @param availability   the period's availability multiplier: events × random outages
 * @param inboundLead    dispatch-weighted inbound transport lead in periods; null where nothing was
 *                       dispatched toward this node
 * @param baselineOnHand {@link #onHand} in the undisrupted baseline set; on a baseline
 *                       run, a copy of {@link #onHand} itself
 * @param baselineServed {@link #served} in the undisrupted baseline set; likewise on a baseline run
 */
@Schema(name = "NodeTimeseries",
        description = "One node's per-period series for a run. Every array has length = horizon "
                + "and index t is period t. `inboundLead` may contain nulls — a period in which "
                + "nothing was dispatched toward the node has no inbound lead, and 0 would claim "
                + "instantaneous delivery.")
public record NodeTimeseriesDto(

        @Schema(description = "Node id.", example = "12")
        long nodeId,

        @Schema(description = "Node name, read from the run's (frozen) network.", example = "DC-1")
        String name,

        @Schema(description = "End-of-period on-hand stock, per period.")
        double[] onHand,

        @Schema(description = "Inbound pipeline at period end, per period.")
        double[] inTransit,

        @Schema(description = "Delivered by the pipeline this period, per period.")
        double[] arrivals,

        @Schema(description = "Demand met here; 0 anywhere but a CUSTOMER.")
        double[] served,

        @Schema(description = "Demand wanted and not got; 0 anywhere but a CUSTOMER.")
        double[] unserved,

        @Schema(description = "Flow across this node's own capacity arc — production or "
                + "pass-through. Stock shipped out does not cross it.")
        double[] throughput,

        @Schema(description = "Availability multiplier in [0,1]: scenario events times random "
                + "outages.")
        double[] availability,

        @Schema(description = "Dispatch-weighted inbound transport lead in periods. **Nullable per "
                + "entry**: null means nothing was dispatched toward this node in that period.")
        List<Double> inboundLead,

        @Schema(description = "On-hand stock in the same period of the undisrupted baseline set. On "
                + "a baseline run (FR-17) this is a copy of `onHand`.")
        double[] baselineOnHand,

        @Schema(description = "Demand met in the same period of the undisrupted baseline set. On a "
                + "baseline run this is a copy of `served`.")
        double[] baselineServed) {
}
