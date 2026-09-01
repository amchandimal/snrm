package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One link's whole horizon, as parallel arrays indexed by period (FR-18).
 *
 * <p>The arc-side twin of {@link NodeTimeseriesDto}, which states why the shape is parallel arrays.
 *
 * <p><strong>{@link #flow()} is what was dispatched, not what arrived.</strong> An arc with a lead
 * time lands its flow at the target in a <em>later</em> period, so a ribbon animated from
 * this array shows material leaving now and the target's {@code arrivals} shows it landing later.
 * That one-period offset is the mechanic a playback view is most likely to render wrongly —
 * {@code samples/four-echelon-playback/README.md} §8.3 works it through on a chain of three
 * one-period legs.
 *
 * <p><strong>{@link #utilisation()} carries nulls in two different situations, and neither is a
 * zero.</strong> An arc with no declared capacity has no fraction to be at — "an unconstrained
 * element cannot be partly disrupted" — and an arc an event has taken to zero available capacity is
 * dark rather than idle, where {@code 0/0} is undefined. A link that simply carried nothing through
 * a period of full availability is a true {@code 0.0}, and a view must not draw the three the same.
 *
 * <p>The endpoint names are carried rather than only the ids because a link has no name of its own
 * and "DC-1 → CUST-1" is the only legible label for one; resolving it client-side would mean holding
 * the whole node list to render a single arc's chart.
 *
 * @param linkId       the link's persistent id
 * @param sourceName   the arc's tail node name, read from the (frozen) network
 * @param targetName   the arc's head node name
 * @param flow         dispatched onto the arc this period, mean across the disrupted replications
 * @param utilisation  {@link #flow} over the capacity actually available; null where the arc is
 *                     uncapped and null where an outage left it none
 * @param availability the period's availability multiplier: events × random outages
 * @param baselineFlow {@link #flow} in the undisrupted baseline set; on a baseline run,
 *                     a copy of {@link #flow} itself
 */
@Schema(name = "LinkTimeseries",
        description = "One link's per-period series for a run. Every array has length = horizon "
                + "and index t is period t. `flow` is what was DISPATCHED in that period; on an arc "
                + "with a lead time it arrives at the target later. `utilisation` may contain "
                + "nulls — an uncapped arc has no utilisation, and neither has one an outage took "
                + "to zero capacity (0/0 is not 0).")
public record LinkTimeseriesDto(

        @Schema(description = "Link id.", example = "3")
        long linkId,

        @Schema(description = "Tail node name.", example = "DC-1")
        String sourceName,

        @Schema(description = "Head node name.", example = "CUST-1")
        String targetName,

        @Schema(description = "Dispatched onto the arc this period, per period.")
        double[] flow,

        @Schema(description = "Flow over available capacity. **Nullable per entry**: null where the "
                + "arc declares no capacity, and where an outage left it none.")
        List<Double> utilisation,

        @Schema(description = "Availability multiplier in [0,1]: scenario events times random "
                + "outages.")
        double[] availability,

        @Schema(description = "Flow in the same period of the undisrupted baseline set. On a "
                + "baseline run (FR-17) this is a copy of `flow`.")
        double[] baselineFlow) {
}
