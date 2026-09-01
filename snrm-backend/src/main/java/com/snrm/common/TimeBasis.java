package com.snrm.common;

import java.util.Objects;

/**
 * A network's clock, reduced to the two things a converter needs: how long a period is, and how a
 * remainder is rounded (FR-13).
 *
 * <p>This is the one place the unit system meets the engines. Everything upstream of it —
 * {@link DurationAmount}, {@link Rate}, {@link TimeUnit} — exists so a user can say "36 hours" and
 * have it stored as "36 hours". Everything downstream of it counts whole periods and per-period
 * quantities and has no idea units were ever involved. {@code NetworkGraphFactory} builds
 * a snapshot through this class precisely so that no metric, no simulation step and no Phase 2
 * search strategy ever has to import {@link TimeUnit}.
 *
 * <h2>The conversions</h2>
 *
 * <p>Both start by reducing everything to seconds, which is what makes a lead time in hours
 * comparable with a period in days without a conversion table:
 *
 * <pre>
 *   periodSeconds   = period_length_value × secondsOf(period_length_unit)
 *   durationPeriods = round( duration_seconds / periodSeconds , rounding_policy )
 *   ratePerPeriod   = rate_value × ( periodSeconds / secondsOf(rate_time_unit) )
 * </pre>
 *
 * <p>which are {@link #periodSeconds()}, {@link #toPeriods(DurationAmount)} and
 * {@link #toPerPeriod(Rate)} respectively; {@link #toExactPeriods(DurationAmount)} is the middle one
 * without the rounding, which is what the resolution validation measures against.
 *
 * <p><strong>Durations round; rates do not.</strong> A duration has to land on the discrete-time
 * grid — flow can only be delayed by a whole number of steps — so the remainder has to go
 * somewhere, and {@link RoundingPolicy} is the network's recorded answer to where. A rate is only
 * rescaled: 120 per week over a one-day period is 17.142857… per period, and nothing is lost or
 * invented in saying so. That asymmetry is why {@link #toPeriods(DurationAmount)} returns a
 * {@code long} and {@link #toPerPeriod(Rate)} a {@code Double}, and why only the duration side can
 * produce the validation findings.
 *
 * <p><strong>Immutable and shareable.</strong> Every field is final and primitive or an enum
 * constant; the {@link DurationAmount} a basis is built from is read once, in the factory method,
 * and never retained — that type is mutable and owned by an entity, and a snapshot holding a live
 * reference to one would change under a Monte Carlo replication mid-run. Instances are therefore
 * safe to publish to the virtual threads without synchronisation, which is the property
 * {@code NetworkGraph} needs and the reason this is a class rather than a record over a
 * {@code DurationAmount}.
 */
public final class TimeBasis {

    private final double periodValue;
    private final TimeUnit periodUnit;
    private final double periodSeconds;
    private final RoundingPolicy roundingPolicy;

    private TimeBasis(double periodValue, TimeUnit periodUnit, RoundingPolicy roundingPolicy) {
        if (!(periodValue > 0)) {
            throw new IllegalArgumentException(
                    "period length must be positive, was " + periodValue + " " + periodUnit);
        }
        this.periodValue = periodValue;
        this.periodUnit = Objects.requireNonNull(periodUnit, "periodUnit");
        this.roundingPolicy = Objects.requireNonNull(roundingPolicy, "roundingPolicy");
        this.periodSeconds = periodUnit.secondsOf(periodValue);
    }

    /**
     * The basis of a network stepping in {@code periodValue} {@code periodUnit}s.
     *
     * @throws IllegalArgumentException if the period is not strictly positive — every conversion
     *                                  here divides by it. {@code ck_network_period} forbids it in
     *                                  the schema and {@code TimeBaseRequest} in the API, so
     *                                  reaching this is a bug rather than bad input.
     */
    public static TimeBasis of(double periodValue, TimeUnit periodUnit, RoundingPolicy policy) {
        return new TimeBasis(periodValue, periodUnit, policy);
    }

    /**
     * The basis of a network, from the two properties that define its clock —
     * {@code network.period_length} and {@code network.rounding_policy}.
     *
     * <p>The period length is copied out, not held: see the class Javadoc.
     */
    public static TimeBasis of(DurationAmount periodLength, RoundingPolicy policy) {
        Objects.requireNonNull(periodLength, "periodLength");
        return new TimeBasis(periodLength.getValue(), periodLength.getUnit(), policy);
    }

    // -------------------------------------------------------------------- durations

    /**
     * This duration as a real number of periods, before rounding — {@code d.seconds / periodSeconds}.
     *
     * <p>The number the validation measures the rounded answer against, and the only
     * form in which "36 hours is one and a half days" can be stated at all. The engines never see
     * it; they take {@link #toPeriods(DurationAmount)}.
     */
    public double toExactPeriods(DurationAmount duration) {
        return secondsOf(duration) / periodSeconds;
    }

    /**
     * This duration as a whole number of periods, rounded by the network's policy —
     * a link's lead time as a pipeline delay, a node's processing time as dwell, an event's window
     * as a range of steps.
     *
     * <p>Can legitimately return 0: a duration shorter than half a period under
     * {@link RoundingPolicy#NEAREST}, or shorter than a whole one under {@link RoundingPolicy#DOWN},
     * has no room on this grid. That is not an error here — the engine is entitled to a number —
     * but it is exactly what {@code TimeValidationService} reports as
     * {@code DURATION_ROUNDS_TO_ZERO}, because a lead time that silently becomes
     * instantaneous flatters the network.
     */
    public long toPeriods(DurationAmount duration) {
        return roundingPolicy.round(toExactPeriods(duration));
    }

    // ------------------------------------------------------------------------ rates

    /**
     * This rate restated over one period — capacity available in a period, demand arising in one,
     * holding cost accrued over one.
     *
     * @return the per-period quantity, or null if the rate is unconstrained (a null
     *         {@link Rate#getValue()}, which is how an uncapped node or link is expressed)
     */
    public Double toPerPeriod(Rate rate) {
        if (rate == null || rate.getValue() == null) {
            return null;
        }
        // Parenthesised deliberately: regrouping the multiply and the divide would give two
        // subtly different floating-point results.
        return rate.getValue() * (periodSeconds / rate.getTimeUnit().secondsOf());
    }

    // -------------------------------------------------------------------- the clock

    /** How many of {@link #periodUnit()} one period is — the 1 in "1 day". */
    public double periodValue() {
        return periodValue;
    }

    /** The unit one period is stated in. */
    public TimeUnit periodUnit() {
        return periodUnit;
    }

    /** One period in seconds; the denominator of every conversion here. */
    public double periodSeconds() {
        return periodSeconds;
    }

    /** How a duration that does not divide evenly into a period is discretised. */
    public RoundingPolicy roundingPolicy() {
        return roundingPolicy;
    }

    /**
     * The period length as a fresh {@link DurationAmount} — for a response DTO or a comparison
     * against another network's clock.
     *
     * <p>A new instance every call, deliberately: that type is mutable, and handing out one this
     * basis kept would let a caller edit the clock of every snapshot sharing it.
     */
    public DurationAmount periodLength() {
        return DurationAmount.of(periodValue, periodUnit);
    }

    private static double secondsOf(DurationAmount duration) {
        // Computed from value and unit rather than read from getSeconds(), so a basis converts the
        // same way whether the DurationAmount came from a JPA row (where seconds is a persisted
        // derived column) or was built in memory a moment ago.
        return duration.getUnit().secondsOf(duration.getValue());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TimeBasis that)) {
            return false;
        }
        return Double.compare(periodValue, that.periodValue) == 0
                && periodUnit == that.periodUnit
                && roundingPolicy == that.roundingPolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(periodValue, periodUnit, roundingPolicy);
    }

    @Override
    public String toString() {
        return "period " + periodValue + " " + periodUnit + ", rounding " + roundingPolicy;
    }
}
