package com.snrm.scenario;

import com.snrm.common.DomainException;
import com.snrm.network.TimeCheck;

/**
 * A disruption event whose window ends after the run does — refused rather than reported.
 *
 * <p>The event is defined but its effect is never fully observed, so every recovery metric computed
 * over it measures the truncation instead of the network: time-to-recovery is bounded
 * by whatever is left of the horizon, and the resilience triangle is missing the side that closes
 * it. Both come back as numbers, which is what makes this worth refusing at the write rather than
 * leaving to the reader of a results dashboard.
 *
 * <p><strong>Why this is a refusal here and a finding in the editor.</strong>
 * {@code TimeValidationService} reports the same condition as a dismissible banner entry
 * because a network mid-edit is entitled to be half-built. An event is not mid-edit:
 * it arrives whole, against a named network, and there is exactly one remedy — extend
 * {@code horizon_periods}, shorten the event, or start it earlier. The shared arithmetic lives on
 * {@code TimeValidationInput.EventWindow} so the two cannot drift into disagreeing about which
 * period an event ends in.
 *
 * <p>The {@code code} is {@link TimeCheck#EVENT_EXCEEDS_HORIZON}, deliberately the same constant the
 * banner groups by: a client that already knows how to explain that finding should not need a second
 * name for the same rule.
 *
 * <p>422: every field is well-formed, and the request is unprocessable only next to this particular
 * network's clock.
 */
public class EventHorizonException extends DomainException {

    /** Problem-detail {@code code}; shared with the finding. Part of the API contract. */
    public static final String CODE = "EVENT_EXCEEDS_HORIZON";

    private final long networkId;
    private final long startPeriod;
    private final long windowPeriods;
    private final long endPeriod;
    private final int horizonPeriods;

    /**
     * @param networkId      the network the event was authored against
     * @param startPeriod    the step the event fires in, on that network's clock
     * @param windowPeriods  how many steps it lasts
     * @param endPeriod      {@code startPeriod + windowPeriods}
     * @param horizonPeriods how many steps the run covers
     */
    EventHorizonException(long networkId, long startPeriod, long windowPeriods, long endPeriod,
            int horizonPeriods) {
        super(("On network %d this event starts at period %d and lasts %d, so it ends at period %d "
                + "— after the horizon of %d. Its recovery would never be observed, and any metric "
                + "over it would measure the truncation. Extend horizonPeriods, shorten the event, "
                + "or start it earlier.")
                .formatted(networkId, startPeriod, windowPeriods, endPeriod, horizonPeriods));
        this.networkId = networkId;
        this.startPeriod = startPeriod;
        this.windowPeriods = windowPeriods;
        this.endPeriod = endPeriod;
        this.horizonPeriods = horizonPeriods;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The network whose clock and horizon the event was measured against. */
    public long getNetworkId() {
        return networkId;
    }

    /** The step the event fires in. */
    public long getStartPeriod() {
        return startPeriod;
    }

    /** How many steps the event lasts. */
    public long getWindowPeriods() {
        return windowPeriods;
    }

    /** The first period after the event. */
    public long getEndPeriod() {
        return endPeriod;
    }

    /** How many periods the run covers. */
    public int getHorizonPeriods() {
        return horizonPeriods;
    }
}
