package com.snrm.common;

/**
 * The unit a duration or a rate is expressed in (FR-13).
 *
 * <p>Every quantity in the model that used to be counted "per period" or "in periods" now
 * carries one of these, so a network can be modelled in hours and another in weeks without the two
 * meaning different things. {@link #secondsOf()} is the common denominator: the derived
 * {@code *_seconds} columns of {@code V3__time_units.sql} are the value multiplied by it, which is
 * what lets the engines compare a lead time stated in days against a period stated in hours
 * without carrying the unit through every calculation.
 *
 * <p><strong>{@link #MONTH} is 30 days and {@link #YEAR} is 365 days, always.</strong> Neither
 * follows the calendar: no leap years, no 31-day months, no DST. That is a deliberate loss of
 * calendar fidelity in exchange for reproducibility — a duration must convert
 * to the same number of seconds regardless of which date a run happens to start on, or identical
 * inputs would stop producing identical outputs. Model a real calendar horizon in {@link #DAY} if
 * that fidelity matters.
 *
 * <p>Persisted as a MySQL {@code ENUM} — the literals must stay in step with
 * {@link #COLUMN_DEFINITION}, and changing either one is a Flyway migration, never an edit.
 *
 * <p>Note for callers: this shadows {@code java.util.concurrent.TimeUnit}. Import it explicitly.
 */
public enum TimeUnit {

    SECOND(1L),

    MINUTE(60L),

    HOUR(3_600L),

    DAY(86_400L),

    WEEK(604_800L),

    /** Fixed at 30 days; see the class Javadoc on why this does not follow the calendar. */
    MONTH(2_592_000L),

    /** Fixed at 365 days; see the class Javadoc on why this does not follow the calendar. */
    YEAR(31_536_000L);

    /**
     * The MySQL column type every {@code *_unit} column uses.
     *
     * <p>A compile-time constant so it can be referenced from {@code @Column(columnDefinition = ...)}
     * at each mapping site. Hibernate maps a {@code STRING} enum to {@code varchar}, which is not
     * what MySQL reports for an {@code ENUM}, and {@code ddl-auto=validate} would reject the
     * mismatch — the same reason {@code NodeType} and {@code MetricScope} pin theirs.
     */
    public static final String COLUMN_DEFINITION =
            "ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR')";

    private final long seconds;

    TimeUnit(long seconds) {
        this.seconds = seconds;
    }

    /** Seconds in one of this unit — {@code DAY.secondsOf() == 86_400}. */
    public long secondsOf() {
        return seconds;
    }

    /**
     * Seconds in {@code count} of this unit — {@code DAY.secondsOf(1.5) == 129_600}.
     *
     * <p>This is what fills the derived {@code *_seconds} columns, and it accepts a fractional
     * count on purpose: half a day is a legitimate lead time.
     */
    public double secondsOf(double count) {
        return count * seconds;
    }

    /**
     * How many of this unit {@code seconds} amounts to — the inverse of {@link #secondsOf(double)},
     * for presenting a stored second-count back in a chosen unit.
     */
    public double fromSeconds(double seconds) {
        return seconds / this.seconds;
    }
}
