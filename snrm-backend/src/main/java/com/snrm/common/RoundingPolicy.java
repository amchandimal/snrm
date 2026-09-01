package com.snrm.common;

/**
 * How a real-valued quantity of periods is reduced to a whole number of them (FR-13).
 *
 * <p>Once durations are stated in their own units they no longer divide evenly into the
 * simulation's period. A 36-hour lead time on a network stepping in days is 1.5 periods, and the
 * discrete-time engine can only delay flow by a whole number of them. This enum is the
 * network-level answer to what happens to the remainder, so the choice is recorded next to the data
 * that provoked it rather than hidden in the engine.
 *
 * <p>The choice is not neutral for resilience results: {@link #DOWN} systematically understates
 * lead times and disruption durations and so flatters the network, {@link #UP} does the opposite.
 * {@link #NEAREST} is the default because it is unbiased across many values, but a study comparing
 * variants should fix one policy across all of them — it is a property of the network, and a
 * variant that changes it is not comparing like with like.
 *
 * <p>Persisted as a MySQL {@code ENUM}; keep the literals in step with {@link #COLUMN_DEFINITION}.
 */
public enum RoundingPolicy {

    /** Half-up to the closest whole period; 1.5 → 2, 1.4 → 1. Unbiased over many values. */
    NEAREST,

    /** Ceiling; any remainder costs a whole period. The conservative choice. */
    UP,

    /** Floor; any remainder is discarded. The optimistic choice. */
    DOWN;

    /** The MySQL column type of {@code network.rounding_policy}; see {@link TimeUnit#COLUMN_DEFINITION}. */
    public static final String COLUMN_DEFINITION = "ENUM('NEAREST','UP','DOWN')";

    /**
     * Applies this policy to a real number of periods.
     *
     * <p>Kept here rather than in the engine so every caller that has to discretise a duration —
     * flow delay, event windows, recovery ramps — rounds the same way.
     *
     * @param periods the exact, possibly fractional, number of periods
     * @return the whole number of periods to use
     */
    public long round(double periods) {
        return switch (this) {
            case NEAREST -> Math.round(periods);
            case UP -> (long) Math.ceil(periods);
            case DOWN -> (long) Math.floor(periods);
        };
    }
}
