package com.snrm.common;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;

/**
 * A length of time as the user stated it — a value, its {@link TimeUnit}, and the derived number of
 * seconds the pair amounts to (FR-13).
 *
 * <p>Embedded wherever the model used to count periods: {@code network.period_length},
 * {@code node.processing_time}, {@code link.lead_time}, and a disruption event's
 * {@code start_offset} and {@code duration}. Each site renames the three columns with
 * {@code @AttributeOverride}; the types and the {@code ENUM} definition come from here.
 *
 * <p><strong>Why store the seconds at all.</strong> Value and unit are what the user typed and what
 * the UI must show back — throwing the unit away and keeping only seconds would turn "2 weeks" into
 * "1209600 seconds" on the next page load. But nothing can be compared, sorted or indexed across
 * mixed units without a common denominator, so the seconds are carried alongside as a derived
 * column rather than recomputed per query. The index is on those columns.
 *
 * <p><strong>Why the callback lives on the entity.</strong> {@link #refreshDerived()} must run
 * before every insert and update or {@link #getSeconds()} silently rots. JPA does not fire
 * {@code @PrePersist} / {@code @PreUpdate} on embeddable classes — only on entities, mapped
 * superclasses and entity listeners — so the annotation cannot go here. Every entity embedding this
 * type declares its own callback and calls {@link #refresh(DurationAmount)} from it. If you embed
 * it somewhere new, add the callback too; there is no framework hook that will do it for you.
 *
 * <p>Mutable, because JPA needs a no-arg constructor and setters to hydrate it, and because the
 * DTO mappers write into an already-attached entity. Treat an instance as owned by exactly one
 * entity — use {@link #copy()} when duplicating a row, as network cloning does.
 */
@Embeddable
public class DurationAmount {

    /** Column names are placeholders: every embedding site overrides all three. */
    @Column(name = "value", nullable = false)
    private double value;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, columnDefinition = TimeUnit.COLUMN_DEFINITION)
    private TimeUnit unit = TimeUnit.DAY;

    /**
     * {@code value × unit.secondsOf()}, maintained by {@link #refreshDerived()}. Never write it
     * directly — it is derived data, and the setter exists only for JPA.
     *
     * <p>A whole number of seconds, per the {@code BIGINT}. The second is the
     * finest unit the model has, so it is the natural grain for the canonical form; a value that
     * lands between two of them — 0.5 SECOND, or 1.5 MINUTE — is rounded here and nowhere else.
     * The stated {@link #getValue()} keeps the user's number exactly either way, so nothing is lost
     * from what is displayed or exported, only from what is compared.
     */
    @Column(name = "seconds", nullable = false)
    private long seconds;

    protected DurationAmount() {
        // for JPA
    }

    private DurationAmount(double value, TimeUnit unit) {
        this.value = value;
        this.unit = unit;
        refreshDerived();
    }

    /** A duration of {@code value} {@code unit}s, with the derived seconds already computed. */
    public static DurationAmount of(double value, TimeUnit unit) {
        return new DurationAmount(value, Objects.requireNonNull(unit, "unit"));
    }

    /** Zero, in the given unit — the default for {@code node.processing_time} and a link's lead time. */
    public static DurationAmount zero(TimeUnit unit) {
        return of(0, unit);
    }

    /**
     * Null-safe {@link #refreshDerived()}, for the {@code @PrePersist} / {@code @PreUpdate}
     * callbacks on the owning entities.
     */
    public static void refresh(DurationAmount amount) {
        if (amount != null) {
            amount.refreshDerived();
        }
    }

    /** Recomputes {@link #getSeconds()} from the current value and unit, to the nearest second. */
    public void refreshDerived() {
        this.seconds = unit == null ? 0 : Math.round(unit.secondsOf(value));
    }

    /** An independent copy, so a cloned entity does not share this instance with its original. */
    public DurationAmount copy() {
        return of(value, unit);
    }

    /**
     * This duration expressed in whole periods of {@code period}, rounded by {@code policy}
     * — how the discrete-time engine turns a stated duration into a step count.
     */
    public long inPeriods(DurationAmount period, RoundingPolicy policy) {
        double periodSeconds = period.getSeconds();
        if (periodSeconds <= 0) {
            throw new IllegalArgumentException("period length must be positive, was " + periodSeconds);
        }
        return policy.round(seconds / periodSeconds);
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
        refreshDerived();
    }

    public TimeUnit getUnit() {
        return unit;
    }

    public void setUnit(TimeUnit unit) {
        this.unit = unit;
        refreshDerived();
    }

    /** The derived total in whole seconds; the sortable, comparable, indexable form. */
    public long getSeconds() {
        return seconds;
    }

    /** For JPA only — {@link #refreshDerived()} owns this value. */
    protected void setSeconds(long seconds) {
        this.seconds = seconds;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DurationAmount that)) {
            return false;
        }
        return Double.compare(value, that.value) == 0 && unit == that.unit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, unit);
    }

    @Override
    public String toString() {
        return value + " " + unit;
    }
}
