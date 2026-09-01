package com.snrm.common;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;

/**
 * A quantity per unit time — a value and the {@link TimeUnit} it is measured over (FR-13).
 *
 * <p>Embedded wherever the model used to say "per period": a node's and a link's throughput
 * capacity, and a node–product row's demand and holding cost. Each site renames the two columns
 * with {@code @AttributeOverride}.
 *
 * <p><strong>No derived column, unlike {@link DurationAmount}.</strong> The normalised form of a
 * rate is a value per second, which for a demand of 120 per week is 0.000198…: a number whose
 * stored precision would be doing real work in every metric that touches it, and which nothing
 * queries, sorts or indexes by. Durations are compared across rows — "which events overlap this
 * window" — so they earn a persisted denominator; rates are read a row at a time and converted in
 * memory by {@link #perSecond()} or {@link #per(TimeUnit)}. Keep it that way unless a query needs
 * to rank rows by rate, which is when the column would start paying for itself.
 *
 * <p>A null {@link #getValue()} means <em>unconstrained</em> where the column allows it — that is
 * how {@code node.capacity_value} and {@code link.capacity_value} keep the meaning the nullable
 * {@code capacity_per_period} had before V3. The unit stays non-null regardless, so the pair is
 * always well-formed and setting a value later does not need a unit chosen for it.
 *
 * <p>Mutable for the same reasons as {@link DurationAmount}, with the same ownership rule: one
 * instance per entity, {@link #copy()} when duplicating a row.
 */
@Embeddable
public class Rate {

    /** Column names are placeholders: every embedding site overrides both. */
    @Column(name = "value")
    private Double value;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_unit", nullable = false, columnDefinition = TimeUnit.COLUMN_DEFINITION)
    private TimeUnit timeUnit = TimeUnit.DAY;

    protected Rate() {
        // for JPA
    }

    private Rate(Double value, TimeUnit timeUnit) {
        this.value = value;
        this.timeUnit = timeUnit;
    }

    /** {@code value} per {@code timeUnit}; a null value means unconstrained. */
    public static Rate of(Double value, TimeUnit timeUnit) {
        return new Rate(value, Objects.requireNonNull(timeUnit, "timeUnit"));
    }

    /** Unconstrained — a null value carrying a unit, ready to be given one. */
    public static Rate unconstrained(TimeUnit timeUnit) {
        return of(null, timeUnit);
    }

    /** Zero per {@code timeUnit} — the default for demand and holding cost. */
    public static Rate zero(TimeUnit timeUnit) {
        return of(0d, timeUnit);
    }

    /** An independent copy, so a cloned entity does not share this instance with its original. */
    public Rate copy() {
        return of(value, timeUnit);
    }

    /** This rate per second, or null if unconstrained — the normalised form, computed not stored. */
    public Double perSecond() {
        return value == null ? null : value / timeUnit.secondsOf();
    }

    /** This rate restated over {@code target}, or null if unconstrained. */
    public Double per(TimeUnit target) {
        Double perSecond = perSecond();
        return perSecond == null ? null : perSecond * target.secondsOf();
    }

    /**
     * How much accrues over {@code duration} — capacity available in a period, demand realised in
     * one, holding cost incurred over one. Null if unconstrained.
     */
    public Double over(DurationAmount duration) {
        Double perSecond = perSecond();
        return perSecond == null ? null : perSecond * duration.getSeconds();
    }

    /** The stated value, or null for unconstrained. */
    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public void setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Rate that)) {
            return false;
        }
        return Objects.equals(value, that.value) && timeUnit == that.timeUnit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, timeUnit);
    }

    @Override
    public String toString() {
        return value + " per " + timeUnit;
    }
}
