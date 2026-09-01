package com.snrm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The three conversions — the single point where the unit system meets the engines.
 *
 * <pre>
 *   periodSeconds   = period_length_value × secondsOf(period_length_unit)
 *   durationPeriods = round( duration_seconds / periodSeconds , rounding_policy )
 *   ratePerPeriod   = rate_value × ( periodSeconds / secondsOf(rate_time_unit) )
 * </pre>
 *
 * <p>Everything downstream of this class counts whole periods and per-period quantities and cannot
 * tell that units ever existed. That is what makes these three formulas worth
 * pinning: an error here is invisible in the engines and shows up only as a resilience number that
 * is quietly wrong.
 */
class TimeBasisTest {

    private static final TimeBasis ONE_DAY_NEAREST =
            TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.NEAREST);

    @Nested
    @DisplayName("periodSeconds")
    class PeriodSeconds {

        @ParameterizedTest(name = "a period of {0} {1} is {2}s")
        @CsvSource({
                "1, DAY,    86400",
                "2, DAY,    172800",
                "1, WEEK,   604800",
                "12, HOUR,  43200",
                "30, MINUTE, 1800"
        })
        void isValueTimesUnit(double value, TimeUnit unit, double expected) {
            assertThat(TimeBasis.of(value, unit, RoundingPolicy.NEAREST).periodSeconds())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a non-positive period is rejected at construction")
        void rejectsNonPositivePeriod() {
            // Every conversion here divides by it. ck_network_period forbids it in the schema and
            // TimeBaseRequest in the API, so reaching this is a bug, not bad input — but it must
            // fail loudly rather than produce an infinity that propagates into a metric.
            assertThatThrownBy(() -> TimeBasis.of(0, TimeUnit.DAY, RoundingPolicy.NEAREST))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
            assertThatThrownBy(() -> TimeBasis.of(-1, TimeUnit.DAY, RoundingPolicy.NEAREST))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("durationPeriods")
    class Durations {

        @Test
        @DisplayName("36 h on a 1-day period is exactly 1.5 periods")
        void exactPeriodsIsUnrounded() {
            assertThat(ONE_DAY_NEAREST.toExactPeriods(DurationAmount.of(36, TimeUnit.HOUR)))
                    .isCloseTo(1.5, within(1e-12));
        }

        @Test
        @DisplayName("the rounding policy decides where the remainder goes")
        void roundsByPolicy() {
            DurationAmount thirtySixHours = DurationAmount.of(36, TimeUnit.HOUR);

            assertThat(TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.NEAREST)
                    .toPeriods(thirtySixHours)).isEqualTo(2);
            assertThat(TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.UP)
                    .toPeriods(thirtySixHours)).isEqualTo(2);
            assertThat(TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.DOWN)
                    .toPeriods(thirtySixHours)).isEqualTo(1);
        }

        @Test
        @DisplayName("6 h on a 1-day period converts to zero periods")
        void aFineDurationVanishes() {
            // The headline case of the resolution validation: the element becomes instantaneous.
            // TimeBasis is entitled to return 0 — the engine needs a number — and it is
            // TimeValidationService that has to say so out loud.
            DurationAmount sixHours = DurationAmount.of(6, TimeUnit.HOUR);
            assertThat(ONE_DAY_NEAREST.toExactPeriods(sixHours)).isCloseTo(0.25, within(1e-12));
            assertThat(ONE_DAY_NEAREST.toPeriods(sixHours)).isZero();
        }

        @Test
        @DisplayName("a coarse duration on a fine period spans many of them")
        void aCoarseDurationSpansManyPeriods() {
            TimeBasis oneHour = TimeBasis.of(1, TimeUnit.HOUR, RoundingPolicy.NEAREST);
            assertThat(oneHour.toPeriods(DurationAmount.of(6, TimeUnit.MONTH))).isEqualTo(4_320);
        }

        @Test
        @DisplayName("conversion reads value and unit, not the persisted derived column")
        void doesNotDependOnTheDerivedColumn() {
            // A DurationAmount built in memory and one hydrated from a row must convert the same
            // way. If TimeBasis read getSeconds() instead, a value changed without its callback
            // running would convert against a stale canonical figure.
            DurationAmount amount = DurationAmount.of(1, TimeUnit.DAY);
            amount.setValue(3);
            assertThat(ONE_DAY_NEAREST.toPeriods(amount)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("ratePerPeriod")
    class Rates {

        @Test
        @DisplayName("capacity 500 per HOUR on a 1-day period")
        void rescalesOntoThePeriod() {
            assertThat(ONE_DAY_NEAREST.toPerPeriod(Rate.of(500d, TimeUnit.HOUR)))
                    .isCloseTo(12_000d, within(1e-9));
        }

        @Test
        @DisplayName("a rate is rescaled, never rounded")
        void isNeverRounded() {
            assertThat(ONE_DAY_NEAREST.toPerPeriod(Rate.of(120d, TimeUnit.WEEK)))
                    .isCloseTo(120d / 7d, within(1e-9));
        }

        @Test
        @DisplayName("a rate stated in the period's own unit passes through unchanged")
        void sameUnitIsIdentity() {
            assertThat(ONE_DAY_NEAREST.toPerPeriod(Rate.of(40d, TimeUnit.DAY)))
                    .isCloseTo(40d, within(1e-12));
        }

        @Test
        @DisplayName("a longer period scales a rate up proportionally")
        void scalesWithThePeriod() {
            TimeBasis oneWeek = TimeBasis.of(1, TimeUnit.WEEK, RoundingPolicy.NEAREST);
            assertThat(oneWeek.toPerPeriod(Rate.of(40d, TimeUnit.DAY)))
                    .isCloseTo(280d, within(1e-9));
        }

        @Test
        @DisplayName("unconstrained stays unconstrained")
        void unconstrainedIsNull() {
            assertThat(ONE_DAY_NEAREST.toPerPeriod(Rate.unconstrained(TimeUnit.DAY))).isNull();
            assertThat(ONE_DAY_NEAREST.toPerPeriod(null)).isNull();
        }
    }

    @Nested
    @DisplayName("as a snapshot-safe value")
    class Immutability {

        @Test
        @DisplayName("does not retain the DurationAmount it was built from")
        void doesNotRetainItsSource() {
            // The bug this guards: DurationAmount is mutable and owned by an entity. A basis
            // holding a live reference would let the network's clock change under a Monte Carlo
            // replication mid-run.
            DurationAmount periodLength = DurationAmount.of(1, TimeUnit.DAY);
            TimeBasis basis = TimeBasis.of(periodLength, RoundingPolicy.NEAREST);

            periodLength.setValue(7);

            assertThat(basis.periodSeconds()).isEqualTo(86_400d);
            assertThat(basis.periodValue()).isEqualTo(1);
        }

        @Test
        @DisplayName("periodLength() hands out a fresh instance every call")
        void periodLengthIsDefensivelyCopied() {
            TimeBasis basis = TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.NEAREST);

            basis.periodLength().setValue(99);

            assertThat(basis.periodLength().getValue()).isEqualTo(1);
            assertThat(basis.periodSeconds()).isEqualTo(86_400d);
        }

        @Test
        @DisplayName("equality is on the clock, not on identity")
        void equalsIsOnTheClock() {
            assertThat(TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.NEAREST))
                    .isEqualTo(TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.NEAREST))
                    .hasSameHashCodeAs(TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.NEAREST))
                    .isNotEqualTo(TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.UP))
                    .isNotEqualTo(TimeBasis.of(24, TimeUnit.HOUR, RoundingPolicy.NEAREST));
        }
    }
}
