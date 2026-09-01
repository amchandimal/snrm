package com.snrm.metrics.simulated;

import com.snrm.metrics.MetricValue;

import java.util.Arrays;
import java.util.List;

/**
 * Turns per-replication observations into the mean and 95% confidence interval this suite reports.
 *
 * <blockquote>Simulated calculators run once per simulation job, aggregating across replications
 * into mean and 95% confidence intervals.</blockquote>
 *
 * <p>One place, so that eleven calculators cannot come to disagree about what a confidence interval
 * on this suite means, and so that a reader of {@code docs/simulation-verification.md} has one
 * formula to check rather than eleven.
 *
 * <h2>The normal approximation, stated rather than assumed</h2>
 *
 * <pre>
 *   mean ± 1.96 · s / √N          s = the sample standard deviation (N − 1 in the denominator)
 * </pre>
 *
 * <p>Not Student's <em>t</em>. At the default N = 100 the two differ by under 2% of the
 * half-width, and the technology table carries no statistics library to take a
 * <em>t</em>-quantile from — adding one for a correction smaller than the Monte Carlo error itself
 * would be a poor trade. <strong>At small N it does matter</strong>: at N = 5 the normal interval is
 * about 30% too narrow, so a low-replication run's intervals should be read as indicative. The
 * verification document says so, and {@link #MIN_REPLICATIONS_FOR_CI} is where the line is drawn
 * below which no interval is published at all.
 *
 * <p><strong>N = 1 has no interval, not a zero-width one.</strong> A single replication has no
 * sample variance, and reporting {@code [x, x]} would state a certainty that does not exist — which
 * matters, because the hand-checked run of {@code docs/simulation-verification.md} is exactly that
 * case. Null bounds are what {@code metric_result.ci_low} / {@code ci_high} are nullable for.
 */
final class ReplicationStatistics {

    /** The two-sided 95% standard-normal quantile. */
    static final double Z_95 = 1.959963984540054;

    /** Below this, the mean is published and the interval is not. See the class note. */
    static final int MIN_REPLICATIONS_FOR_CI = 2;

    /**
     * Absorbs the representation error in {@code α × n} when counting the CVaR tail — far smaller
     * than one observation and far larger than the error it is there to hide.
     */
    private static final double QUANTILE_TOLERANCE = 1e-9;

    private ReplicationStatistics() {
    }

    /**
     * A network-scoped {@link MetricValue} carrying the mean of these observations and its interval.
     *
     * @param code         the metric code
     * @param observations one number per replication; empty produces no value at all, which is
     *                     legitimate where the metric is undefined for this network, and is not
     *                     an error
     */
    static List<MetricValue> summarise(String code, double[] observations) {
        if (observations.length == 0) {
            return List.of();
        }
        double mean = mean(observations);
        if (observations.length < MIN_REPLICATIONS_FOR_CI) {
            return List.of(MetricValue.network(code, mean));
        }
        double halfWidth = Z_95 * standardDeviation(observations, mean)
                / Math.sqrt(observations.length);
        return List.of(new MetricValue(code, com.snrm.metrics.MetricScope.NETWORK, null, null,
                mean, mean - halfWidth, mean + halfWidth, null));
    }

    /**
     * The same, with the mean and both bounds clamped into {@code [lower, upper]}.
     *
     * <p>For the metrics that carry a stated range — {@code RESILIENCE_INDEX} is "(0–1)", and every
     * fill rate is a proportion. Clamping the <em>bounds</em> as well as the mean is the point: a
     * ratio whose mean is 0.98 can easily produce an upper bound of 1.02 under the normal
     * approximation, and a confidence interval that extends past the metric's own definition reads
     * as a defect whatever the arithmetic says.
     */
    static List<MetricValue> summariseBounded(String code, double[] observations, double lower,
            double upper) {
        List<MetricValue> summary = summarise(code, observations);
        if (summary.isEmpty()) {
            return summary;
        }
        MetricValue value = summary.get(0);
        return List.of(new MetricValue(code, value.scope(), value.scopeId(), value.scopeName(),
                clamp(value.value(), lower, upper),
                value.ciLow() == null ? null : clamp(value.ciLow(), lower, upper),
                value.ciHigh() == null ? null : clamp(value.ciHigh(), lower, upper),
                value.displayUnit()));
    }

    static double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    /** Sample standard deviation, {@code N − 1} in the denominator — these are a sample, not a population. */
    static double standardDeviation(double[] values, double mean) {
        if (values.length < 2) {
            return 0;
        }
        double sumSquares = 0;
        for (double value : values) {
            double deviation = value - mean;
            sumSquares += deviation * deviation;
        }
        return Math.sqrt(sumSquares / (values.length - 1));
    }

    /**
     * The mean of the worst {@code ⌈N·(1−α)⌉} observations — conditional value at risk
     * ({@code CVAR_COST}, α = 0.95).
     *
     * <p>"Worst" means largest here, because the observations are costs. The count is rounded
     * <em>up</em> and floored at one, so the tail is never empty: at the default N = 100 it is the
     * five most expensive replications, and at N = 1 it is that replication — which is the honest
     * answer, since the worst 5% of a single observation is that observation.
     *
     * <p>This is the empirical CVaR, not a fitted one. With 100 replications the estimate rests on
     * five observations and is correspondingly noisy; the mitigation for that is replication
     * count, and the verification document states what the number is and is not.
     *
     * <p><strong>The tail size is counted from the head, not computed as {@code n(1−α)}.</strong>
     * The two are equal in exact arithmetic — {@code n − ⌊αn⌋ = ⌈n(1−α)⌉} for integer {@code n} — and
     * not in floating point, because neither {@code 0.95} nor {@code 1 − 0.95} is representable:
     * {@code 100 × (1 − 0.95)} evaluates to {@code 5.000000000000004}, whose ceiling is <em>six</em>,
     * so the "worst 5%" would quietly become the worst 6. Counting {@code n − ⌊αn + ε⌋} keeps the
     * arithmetic on the side where a whole number is expected, and the epsilon absorbs the opposite
     * error ({@code 0.95 × 20} evaluates to just under 19).
     */
    static double conditionalValueAtRisk(double[] observations, double alpha) {
        if (observations.length == 0) {
            return 0;
        }
        double[] sorted = observations.clone();
        Arrays.sort(sorted);
        int belowTheTail = (int) Math.floor(alpha * observations.length + QUANTILE_TOLERANCE);
        int tailSize = Math.max(1, Math.min(observations.length - belowTheTail, sorted.length));
        double sum = 0;
        for (int i = sorted.length - tailSize; i < sorted.length; i++) {
            sum += sorted[i];
        }
        return sum / tailSize;
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.min(upper, Math.max(lower, value));
    }
}
