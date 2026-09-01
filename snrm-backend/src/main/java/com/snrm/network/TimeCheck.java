package com.snrm.network;

/**
 * The four resolution checks, one constant each.
 *
 * <p>The constant name is the {@code code} member of a {@link TimeFinding} and is part of the API
 * contract: the editor's warning banner groups by it and the import wizard decides what to refuse on
 * it. Treat a rename as a breaking change.
 *
 * <p>All four are about <em>durations</em>. Rates convert exactly — they are rescaled, never rounded
 * — so there is nothing for a check to catch on that side.
 */
public enum TimeCheck {

    /**
     * A positive duration that converts to 0 periods: 6 hours on a network stepping in days, under
     * {@code NEAREST}. The element becomes instantaneous and the model quietly stops representing
     * what the user entered — a lead time that vanishes flatters the network's resilience, which is
     * the one direction of error a resilience study cannot afford.
     *
     * <p>Severity depends on where the check runs: an error during import, where the
     * remedy is to fix the file or the period before anything is stored, and a warning in the
     * editor, where the user may be mid-edit and is entitled to a half-built network.
     */
    DURATION_ROUNDS_TO_ZERO,

    /**
     * A duration whose rounded value differs from the declared one by more than 10%. The element
     * survives conversion but not intact: 10 hours on a one-day period arrives as a full day, 140%
     * of what was asked for. Always a warning — the value is still there to be seen and corrected.
     */
    DURATION_ROUNDING_ERROR,

    /**
     * The period is more than 1000× finer than the longest declared duration, so spanning that
     * duration costs more than a thousand simulation steps. Nothing is wrong with the model; the run
     * will simply be slow, multiplied by every Monte Carlo replication. A warning, on the
     * element that owns the longest duration.
     */
    PERIOD_TOO_FINE,

    /**
     * A disruption event whose start offset plus duration lands after the end of the run. The event
     * is defined but its effect is never fully observed, so any recovery metric computed over it
     * measures a truncation rather than the network. An error: the remedy is to
     * extend {@code horizon_periods}, and it is unambiguous.
     */
    EVENT_EXCEEDS_HORIZON
}
