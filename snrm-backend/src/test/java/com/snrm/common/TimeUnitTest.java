package com.snrm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The unit table.
 *
 * <p>These are the constants every other conversion in the system multiplies by, so they are pinned
 * to literal second-counts rather than to expressions like {@code 30 * 24 * 60 * 60}. An expression
 * would restate the implementation and pass however wrong it was; a literal is an independent
 * statement of what the number is.
 */
class TimeUnitTest {

    @ParameterizedTest(name = "one {0} is {1} seconds")
    @CsvSource({
            "SECOND, 1",
            "MINUTE, 60",
            "HOUR,   3600",
            "DAY,    86400",
            "WEEK,   604800",
            "MONTH,  2592000",
            "YEAR,   31536000"
    })
    @DisplayName("The unit table, to the second")
    void secondsOfEachUnit(TimeUnit unit, long expectedSeconds) {
        assertThat(unit.secondsOf()).isEqualTo(expectedSeconds);
    }

    @Test
    @DisplayName("MONTH is 30 days and YEAR is 365 days — fixed, not calendar")
    void monthAndYearAreFixedLengthApproximations() {
        assertThat(TimeUnit.MONTH.secondsOf()).isEqualTo(30 * TimeUnit.DAY.secondsOf());
        assertThat(TimeUnit.YEAR.secondsOf()).isEqualTo(365 * TimeUnit.DAY.secondsOf());

        // The consequence that matters: 12 months is not a year, and a year is not 52 weeks. If
        // either ever became true, someone would have quietly introduced calendar arithmetic and
        // broken the reproducibility every run depends on.
        assertThat(12 * TimeUnit.MONTH.secondsOf()).isNotEqualTo(TimeUnit.YEAR.secondsOf());
        assertThat(52 * TimeUnit.WEEK.secondsOf()).isNotEqualTo(TimeUnit.YEAR.secondsOf());
    }

    @Test
    @DisplayName("secondsOf(count) accepts a fractional count")
    void secondsOfFractionalCount() {
        assertThat(TimeUnit.DAY.secondsOf(1.5)).isEqualTo(129_600d);
        assertThat(TimeUnit.HOUR.secondsOf(0.5)).isEqualTo(1_800d);
        assertThat(TimeUnit.WEEK.secondsOf(0)).isZero();
    }

    @ParameterizedTest
    @EnumSource(TimeUnit.class)
    @DisplayName("fromSeconds inverts secondsOf for every unit")
    void fromSecondsIsTheInverse(TimeUnit unit) {
        assertThat(unit.fromSeconds(unit.secondsOf(7.25))).isCloseTo(7.25, within(1e-9));
    }

    @Test
    @DisplayName("COLUMN_DEFINITION lists exactly the constants, in order")
    void columnDefinitionMatchesTheEnum() {
        // ddl-auto=validate compares the mapped column against what MySQL reports, so a constant
        // added here without the same edit to COLUMN_DEFINITION — and to a Flyway migration —
        // fails at startup rather than at the first insert. This catches the first half of that
        // mistake at build time.
        String expected = "ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR')";
        assertThat(TimeUnit.COLUMN_DEFINITION).isEqualTo(expected);

        for (TimeUnit unit : TimeUnit.values()) {
            assertThat(TimeUnit.COLUMN_DEFINITION).contains("'" + unit.name() + "'");
        }
        assertThat(TimeUnit.values()).hasSize(7);
    }

    @Test
    @DisplayName("the constants are ordered finest to coarsest")
    void constantsAreOrderedFinestFirst() {
        // Relied on by anything that picks "the coarsest unit that still fits", including the
        // suggest-period ladder.
        TimeUnit[] units = TimeUnit.values();
        for (int i = 1; i < units.length; i++) {
            assertThat(units[i].secondsOf())
                    .as("%s must be coarser than %s", units[i], units[i - 1])
                    .isGreaterThan(units[i - 1].secondsOf());
        }
    }
}
