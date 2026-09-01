package com.snrm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The duration value object: a stated {@code (value, unit)} pair plus the derived
 * canonical second-count.
 *
 * <p>The derived column is the thing worth testing hardest. It is written by the owning entity's
 * {@code @PrePersist}/{@code @PreUpdate}, read by every cross-unit comparison, and indexed
 * ({@code ix_node_proc}, {@code ix_event_window}) — so a stale one is not a display bug, it is a
 * query returning the wrong rows.
 */
class DurationAmountTest {

    @Nested
    @DisplayName("the derived canonical second-count")
    class DerivedSeconds {

        @ParameterizedTest(name = "{0} {1} = {2}s")
        @CsvSource({
                "1,   DAY,    86400",
                "2,   WEEK,   1209600",
                "36,  HOUR,   129600",
                "90,  MINUTE, 5400",
                "6,   MONTH,  15552000",
                "0,   DAY,    0"
        })
        void isValueTimesUnit(double value, TimeUnit unit, long expectedSeconds) {
            assertThat(DurationAmount.of(value, unit).getSeconds()).isEqualTo(expectedSeconds);
        }

        @Test
        @DisplayName("is recomputed when the value changes")
        void refreshesOnValueChange() {
            DurationAmount amount = DurationAmount.of(1, TimeUnit.DAY);
            amount.setValue(3);
            assertThat(amount.getSeconds()).isEqualTo(259_200L);
        }

        @Test
        @DisplayName("is recomputed when the unit changes")
        void refreshesOnUnitChange() {
            DurationAmount amount = DurationAmount.of(2, TimeUnit.HOUR);
            assertThat(amount.getSeconds()).isEqualTo(7_200L);

            amount.setUnit(TimeUnit.WEEK);
            assertThat(amount.getSeconds()).isEqualTo(1_209_600L);

            // Changing a unit re-displays the value in that unit rather than
            // reinterpreting the number. The stated value must survive untouched.
            assertThat(amount.getValue()).isEqualTo(2);
        }

        @Test
        @DisplayName("refresh() is null-safe, as the entity callbacks need")
        void refreshIsNullSafe() {
            assertThatCode(() -> DurationAmount.refresh(null)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} SECOND rounds to {1}s")
        @CsvSource({"0.4, 0", "0.5, 1", "1.4, 1", "1.5, 2", "2.6, 3"})
        @DisplayName("BIGINT — a sub-second remainder rounds to nearest")
        void roundsToWholeSeconds(double value, long expectedSeconds) {
            DurationAmount amount = DurationAmount.of(value, TimeUnit.SECOND);
            assertThat(amount.getSeconds()).isEqualTo(expectedSeconds);
            // The stated value is never rounded — only the canonical form it is compared through.
            assertThat(amount.getValue()).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("copy() is independent of its original")
        void copyIsIndependent() {
            // The bug this guards: NetworkService clones a network by copying its nodes and links.
            // Sharing one mutable embeddable between original and clone would make an edit to
            // either show up in both, and the two networks are meant to be independently
            // comparable configurations.
            DurationAmount original = DurationAmount.of(1, TimeUnit.DAY);
            DurationAmount copy = original.copy();

            copy.setValue(5);

            assertThat(original.getValue()).isEqualTo(1);
            assertThat(original.getSeconds()).isEqualTo(86_400L);
            assertThat(copy.getSeconds()).isEqualTo(432_000L);
        }

        @Test
        @DisplayName("copy() carries the unit, not just the number")
        void copyCarriesTheUnit() {
            DurationAmount copy = DurationAmount.of(36, TimeUnit.HOUR).copy();
            assertThat(copy.getUnit()).isEqualTo(TimeUnit.HOUR);
            assertThat(copy.getValue()).isEqualTo(36);
        }
    }

    @Nested
    @DisplayName("discretisation onto a period")
    class InPeriods {

        @Test
        @DisplayName("a duration becomes whole periods under the policy")
        void convertsUnderEachPolicy() {
            DurationAmount thirtySixHours = DurationAmount.of(36, TimeUnit.HOUR);
            DurationAmount oneDay = DurationAmount.of(1, TimeUnit.DAY);

            // 1.5 periods exactly.
            assertThat(thirtySixHours.inPeriods(oneDay, RoundingPolicy.NEAREST)).isEqualTo(2);
            assertThat(thirtySixHours.inPeriods(oneDay, RoundingPolicy.UP)).isEqualTo(2);
            assertThat(thirtySixHours.inPeriods(oneDay, RoundingPolicy.DOWN)).isEqualTo(1);
        }

        @Test
        @DisplayName("6 h on a 1-day period rounds away to nothing")
        void aFineDurationCanVanish() {
            assertThat(DurationAmount.of(6, TimeUnit.HOUR)
                    .inPeriods(DurationAmount.of(1, TimeUnit.DAY), RoundingPolicy.NEAREST))
                    .isZero();
        }

        @Test
        @DisplayName("a non-positive period is rejected rather than dividing by zero")
        void rejectsNonPositivePeriod() {
            DurationAmount week = DurationAmount.of(1, TimeUnit.WEEK);
            assertThatThrownBy(() -> week.inPeriods(DurationAmount.of(0, TimeUnit.DAY),
                    RoundingPolicy.NEAREST))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    @Test
    @DisplayName("equality is on the stated pair, not the derived column")
    void equalsIsOnValueAndUnit() {
        assertThat(DurationAmount.of(1, TimeUnit.DAY))
                .isEqualTo(DurationAmount.of(1, TimeUnit.DAY))
                .hasSameHashCodeAs(DurationAmount.of(1, TimeUnit.DAY));

        // 24 HOUR and 1 DAY are the same length of time but not the same statement of it, and the
        // difference is exactly what storing the pair preserves.
        assertThat(DurationAmount.of(24, TimeUnit.HOUR)).isNotEqualTo(DurationAmount.of(1, TimeUnit.DAY));
        assertThat(DurationAmount.of(24, TimeUnit.HOUR).getSeconds())
                .isEqualTo(DurationAmount.of(1, TimeUnit.DAY).getSeconds());
    }

    @Test
    @DisplayName("zero(unit) is a zero-length duration in that unit")
    void zeroFactory() {
        DurationAmount zero = DurationAmount.zero(TimeUnit.WEEK);
        assertThat(zero.getValue()).isZero();
        assertThat(zero.getSeconds()).isZero();
        assertThat(zero.getUnit()).isEqualTo(TimeUnit.WEEK);
    }
}
