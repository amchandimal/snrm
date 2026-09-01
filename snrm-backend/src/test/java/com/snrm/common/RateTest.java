package com.snrm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The rate value object: a {@code (value, time_unit)} pair where the unit is the
 * denominator.
 *
 * <p>Unlike {@link DurationAmount} a rate carries no persisted canonical column — it is rescaled at
 * snapshot build and never rounded — so the conversions here are the whole of its behaviour.
 */
class RateTest {

    @Test
    @DisplayName("capacity 500 units per HOUR")
    void perSecondIsValueOverUnit() {
        Rate capacity = Rate.of(500d, TimeUnit.HOUR);
        assertThat(capacity.perSecond()).isEqualTo(500d / 3600d);
    }

    @Test
    @DisplayName("per(unit) restates the same throughput over another unit")
    void restatesOverAnotherUnit() {
        Rate capacity = Rate.of(500d, TimeUnit.HOUR);

        // isCloseTo throughout: perSecond() divides before per() multiplies, so 500/3600 x 86400
        // is not bit-identical to 500 x 24. The tolerance is far tighter than any error that would
        // matter and far looser than one representation step.
        assertThat(capacity.per(TimeUnit.DAY)).isCloseTo(12_000d, within(1e-9));   // 500 x 24
        assertThat(capacity.per(TimeUnit.MINUTE)).isCloseTo(500d / 60d, within(1e-12));
        assertThat(capacity.per(TimeUnit.HOUR)).isCloseTo(500d, within(1e-12));
    }

    @Test
    @DisplayName("demand 40 units per DAY, over a one-week period")
    void accruesOverADuration() {
        Rate demand = Rate.of(40d, TimeUnit.DAY);
        assertThat(demand.over(DurationAmount.of(1, TimeUnit.WEEK))).isCloseTo(280d, within(1e-9));
        assertThat(demand.over(DurationAmount.of(12, TimeUnit.HOUR))).isCloseTo(20d, within(1e-9));
        assertThat(demand.over(DurationAmount.zero(TimeUnit.DAY))).isZero();
    }

    @Test
    @DisplayName("a rate that does not divide evenly is rescaled, never rounded")
    void isNeverRounded() {
        // The asymmetry: durations land on the discrete grid and so must round; rates are
        // only rescaled, and nothing is lost or invented in saying 120 per week is 17.142857... per
        // day. If this ever came back as 17, the four checks would need a rate arm.
        Rate demand = Rate.of(120d, TimeUnit.WEEK);
        assertThat(demand.per(TimeUnit.DAY)).isCloseTo(120d / 7d, within(1e-12));
        assertThat(demand.per(TimeUnit.DAY)).isNotEqualTo(17d);
    }

    @Test
    @DisplayName("a null value means unconstrained, and stays null through every conversion")
    void unconstrainedIsNullThroughout() {
        // How an uncapped node or link is expressed — the meaning the nullable capacity_per_period
        // column carried before V3 and capacity_value carries now.
        Rate unconstrained = Rate.unconstrained(TimeUnit.DAY);

        assertThat(unconstrained.getValue()).isNull();
        assertThat(unconstrained.perSecond()).isNull();
        assertThat(unconstrained.per(TimeUnit.HOUR)).isNull();
        assertThat(unconstrained.over(DurationAmount.of(1, TimeUnit.WEEK))).isNull();

        // The unit is still there, so giving it a value later does not need one chosen for it.
        assertThat(unconstrained.getTimeUnit()).isEqualTo(TimeUnit.DAY);
    }

    @Test
    @DisplayName("zero is a real constraint, distinct from unconstrained")
    void zeroIsNotUnconstrained() {
        assertThat(Rate.zero(TimeUnit.DAY).perSecond()).isZero();
        assertThat(Rate.zero(TimeUnit.DAY)).isNotEqualTo(Rate.unconstrained(TimeUnit.DAY));
    }

    @Test
    @DisplayName("copy() is independent of its original")
    void copyIsIndependent() {
        Rate original = Rate.of(100d, TimeUnit.DAY);
        Rate copy = original.copy();

        copy.setValue(250d);
        copy.setTimeUnit(TimeUnit.HOUR);

        assertThat(original.getValue()).isEqualTo(100d);
        assertThat(original.getTimeUnit()).isEqualTo(TimeUnit.DAY);
    }

    @Test
    @DisplayName("equality is on value and unit together")
    void equalsIsOnValueAndUnit() {
        assertThat(Rate.of(500d, TimeUnit.HOUR))
                .isEqualTo(Rate.of(500d, TimeUnit.HOUR))
                .hasSameHashCodeAs(Rate.of(500d, TimeUnit.HOUR));

        // Same throughput, different statement of it — kept distinct for the reason the pair
        // is stored at all.
        assertThat(Rate.of(24d, TimeUnit.DAY)).isNotEqualTo(Rate.of(1d, TimeUnit.HOUR));
    }
}
