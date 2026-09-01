package com.snrm.scenario;

import com.snrm.common.DomainException;

/**
 * A disruption event whose target does not resolve against the network it was authored against.
 *
 * <p>{@code disruption_event.target_id} is a polymorphic reference — {@code node.id} or
 * {@code link.id} depending on {@code target_type} — and {@code target_region} is a free-text tag,
 * so neither can carry a foreign key. Every way that reference can be wrong therefore has to be
 * caught here, and all of them are the same mistake from the client's point of view: the event names
 * something the network does not have.
 *
 * <ul>
 *   <li>a NODE or LINK event with no {@code targetId}, or one that names a row in another network;</li>
 *   <li>a REGION event with no {@code targetRegion}, or one naming a tag no node in the network
 *       carries — an event that would strike nothing, which is silent rather than harmless: the
 *       scenario would run, produce a baseline result, and look like evidence of resilience;</li>
 *   <li>either type carrying the other's half of the reference, which {@code ck_event_target}
 *       forbids and which would leave the row with two disagreeing answers to what it hits.</li>
 * </ul>
 *
 * <p>422: the ids and tags are well-formed, but the combination is not something this network can
 * express — the same reading {@link com.snrm.network.CrossNetworkReferenceException} takes.
 */
public class EventTargetException extends DomainException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "EVENT_TARGET_INVALID";

    private final DisruptionTargetType targetType;
    private final long networkId;

    private EventTargetException(String message, DisruptionTargetType targetType, long networkId) {
        super(message);
        this.targetType = targetType;
        this.networkId = networkId;
    }

    /** A NODE or LINK event that named no id at all. */
    static EventTargetException missingId(DisruptionTargetType targetType, long networkId) {
        return new EventTargetException(
                "A %s event must name the id it strikes in targetId.".formatted(targetType),
                targetType, networkId);
    }

    /** A NODE or LINK event whose id is unknown, or belongs to another network. */
    static EventTargetException unresolvedId(DisruptionTargetType targetType, long targetId,
            long networkId) {
        return new EventTargetException(
                ("No %s with id %d in network %d. An event's target is resolved against the network "
                        + "it is authored against, so an id from another variant will not do.")
                        .formatted(targetType.name().toLowerCase(), targetId, networkId),
                targetType, networkId);
    }

    /** A REGION event that named no tag. */
    static EventTargetException missingRegion(long networkId) {
        return new EventTargetException(
                "A REGION event must name the node.region tag it strikes in targetRegion.",
                DisruptionTargetType.REGION, networkId);
    }

    /** A REGION event naming a tag no node in the network carries. */
    static EventTargetException emptyRegion(String region, long networkId) {
        return new EventTargetException(
                ("No node in network %d carries the region tag \"%s\", so this event would strike "
                        + "nothing. GET /api/v1/networks/%d/regions lists the tags that are in use.")
                        .formatted(networkId, region, networkId),
                DisruptionTargetType.REGION, networkId);
    }

    /** An event carrying both halves of the reference, or the half its type does not use. */
    static EventTargetException wrongHalf(DisruptionTargetType targetType, long networkId) {
        String unused = targetType == DisruptionTargetType.REGION ? "targetId" : "targetRegion";
        String used = targetType == DisruptionTargetType.REGION ? "targetRegion" : "targetId";
        return new EventTargetException(
                ("A %s event is addressed by %s; leave %s unset. A row carrying both has two "
                        + "answers to what it strikes, and which one wins would depend on the "
                        + "reader.").formatted(targetType, used, unused),
                targetType, networkId);
    }

    @Override
    public String code() {
        return CODE;
    }

    /** What the event claimed to strike. */
    public DisruptionTargetType getTargetType() {
        return targetType;
    }

    /** The network the target was resolved against. */
    public long getNetworkId() {
        return networkId;
    }
}
