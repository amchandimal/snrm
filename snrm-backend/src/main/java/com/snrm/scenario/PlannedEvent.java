package com.snrm.scenario;

import java.util.Set;

/**
 * One disruption event as the simulation engine sees it: resolved, discretised, and free of JPA.
 *
 * <p>The engine-side counterpart of {@link DisruptionEvent}, and it exists for the same reason
 * {@code GraphNode} exists beside {@code Node}. Three things have already happened by the time one
 * of these is built, and none of them can happen inside a Monte Carlo loop:
 *
 * <ul>
 *   <li><strong>The target is resolved to ids.</strong> A {@code REGION} event names a
 *       {@code node.region} tag, and which nodes carry it is a question about one network that needs
 *       a repository to answer. Resolving it once, at the module edge, is also what makes
 *       the preview of {@code GET /networks/{id}/region-nodes} and the run agree — both run the same
 *       {@code region = ?} query.</li>
 *   <li><strong>The timing is in periods.</strong> {@code startOffset} and {@code duration} are real
 *       durations discretised through the network's {@code TimeBasis} by the same
 *       {@code EventWindow} arithmetic the editor's banner and the write-time refusal use, so the
 *       bar the timeline draws as ending at period 58 is the one the engine stops applying at
 *       period 58.</li>
 *   <li><strong>The recovery profile is the strategy, not the discriminator.</strong> The enum
 *       constant has already been resolved through {@link RecoveryProfiles}, so a replication asks
 *       the profile for {@code availability(t)} without a registry lookup per period per event
 * </li>
 * </ul>
 *
 * <p>Immutable and safe to publish to the virtual threads. The per-replication draws —
 * whether the event occurs at all, and any timing jitter — are <em>not</em> here: they differ by
 * replication, and baking one replication's draw into a shared object is how a hundred threads end
 * up simulating the same future.
 *
 * @param eventId         the {@code disruption_event.id} this came from, for tracing a result back
 * @param label           a short human-readable description for logs and traces, e.g.
 *                        {@code "NODE 12 (DC-1)"} or {@code "REGION EU-North (4 nodes)"}
 * @param targetType      what kind of thing is struck
 * @param nodeIds         node ids the event applies to — one for a {@code NODE} event, the tagged
 *                        set for a {@code REGION} one, empty for a {@code LINK} event
 * @param linkIds         link ids the event applies to — one for a {@code LINK} event, else empty
 * @param startPeriod     the step the event fires in, before any per-replication jitter
 * @param windowPeriods   how many steps it lasts, before jitter. May be 0 where the stated duration
 *                        is finer than the network's period
 * @param severity        capacity-availability multiplier reduction in {@code [0,1]}
 * @param recoveryProfile the strategy that shapes the ramp back
 * @param probability     the chance the event occurs at all in a given replication
 */
public record PlannedEvent(
        long eventId,
        String label,
        DisruptionTargetType targetType,
        Set<Long> nodeIds,
        Set<Long> linkIds,
        long startPeriod,
        long windowPeriods,
        double severity,
        RecoveryProfile recoveryProfile,
        double probability) {

    /** Defensive copies: a plan is shared by every replication and must not be reachable to edit. */
    public PlannedEvent {
        nodeIds = nodeIds == null ? Set.of() : Set.copyOf(nodeIds);
        linkIds = linkIds == null ? Set.of() : Set.copyOf(linkIds);
    }

    /** True when this event happens in every replication — the deterministic case. */
    public boolean isCertain() {
        return probability >= 1.0;
    }

    /**
     * Whether this event can affect anything at all.
     *
     * <p>False for an event whose window rounded to zero periods, or whose severity is zero, or
     * whose target resolved to nothing. The engine skips these rather than applying a multiplier of
     * 1 every period; more importantly, the metrics that are defined relative to a
     * disruption's onset need to know that a replication had no disruption to recover from.
     */
    public boolean hasEffect() {
        return windowPeriods > 0 && severity > 0 && !(nodeIds.isEmpty() && linkIds.isEmpty());
    }

    /** The first period after this event's window, before jitter — {@code start + window}. */
    public long endPeriod() {
        return startPeriod + windowPeriods;
    }
}
