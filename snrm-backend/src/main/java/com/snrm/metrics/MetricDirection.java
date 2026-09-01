package com.snrm.metrics;

/**
 * Which way is better, for one metric of the suite.
 *
 * <p>The comparison view highlights the best cell in each row, and "best" is not
 * derivable from a number: 0.9 is a good {@code FILL_RATE} and a bad {@code LOSS_AREA}. Rather than
 * hard-code a table of codes in the comparison service — a second list to fall out of step with the
 * registry the moment a metric is added — each calculator declares its own direction
 * through {@link MetricCalculator#direction()} and the registry answers the question.
 *
 * <p><strong>{@link #NEUTRAL} is not a gap to be filled in later.</strong> It is the honest answer
 * for the structural statistics: a denser network is better connected <em>and</em> more expensive,
 * a shorter average path is faster <em>and</em> less redundant, and neither is defined as an
 * objective. Highlighting the highest {@code DENSITY} as "best" would put a claim on screen that
 * nothing in the design supports, so those rows are shown without a winner and the reader decides.
 */
public enum MetricDirection {

    /** A larger value is a better configuration — {@code FILL_RATE}, {@code RESILIENCE_INDEX}. */
    HIGHER_IS_BETTER,

    /** A smaller value is a better configuration — {@code TTR}, {@code TOTAL_COST}. */
    LOWER_IS_BETTER,

    /**
     * The metric describes the configuration without ranking it. No cell in the row is highlighted
     * and no arrow is drawn; see the class Javadoc on why this is a decision rather than an omission.
     */
    NEUTRAL;

    /** Whether a row in this direction has a winner to highlight at all. */
    public boolean isRanked() {
        return this != NEUTRAL;
    }

    /**
     * Whether {@code candidate} beats {@code incumbent} under this direction.
     *
     * <p>Strictly: a tie does not displace the incumbent, which is what lets the comparison service
     * collect every tied cell as joint-best rather than arbitrarily picking the first.
     */
    public boolean isBetter(double candidate, double incumbent) {
        return switch (this) {
            case HIGHER_IS_BETTER -> candidate > incumbent;
            case LOWER_IS_BETTER -> candidate < incumbent;
            case NEUTRAL -> false;
        };
    }
}
