package com.snrm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three discretisation policies.
 *
 * <p>The boundaries are the whole point: a policy that is right in the middle of its range and wrong
 * at 0.5 or at exactly 1.0 will pass a careless test and then bias every lead time in a study.
 */
class RoundingPolicyTest {

    @ParameterizedTest(name = "NEAREST({0}) = {1}")
    @CsvSource({
            "0.0, 0", "0.4, 0", "0.5, 1", "0.6, 1",
            "1.0, 1", "1.4, 1", "1.5, 2", "1.9, 2",
            "2.5, 3"
    })
    void nearestIsHalfUp(double periods, long expected) {
        assertThat(RoundingPolicy.NEAREST.round(periods)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "UP({0}) = {1}")
    @CsvSource({
            "0.0, 0", "0.1, 1", "0.5, 1", "0.9, 1",
            "1.0, 1", "1.1, 2", "1.9, 2"
    })
    void upIsCeiling(double periods, long expected) {
        assertThat(RoundingPolicy.UP.round(periods)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "DOWN({0}) = {1}")
    @CsvSource({
            "0.0, 0", "0.1, 0", "0.9, 0",
            "1.0, 1", "1.9, 1", "2.0, 2"
    })
    void downIsFloor(double periods, long expected) {
        assertThat(RoundingPolicy.DOWN.round(periods)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an exact number of periods is unchanged by every policy")
    void exactValuesAreFixedPoints() {
        // The case that must never differ between policies: if it did, the choice of policy would
        // change results for networks whose durations divide evenly, and the rule that the
        // policy only governs the remainder would be false.
        for (double exact : new double[] {0, 1, 2, 7, 52}) {
            assertThat(RoundingPolicy.NEAREST.round(exact)).isEqualTo((long) exact);
            assertThat(RoundingPolicy.UP.round(exact)).isEqualTo((long) exact);
            assertThat(RoundingPolicy.DOWN.round(exact)).isEqualTo((long) exact);
        }
    }

    @Test
    @DisplayName("DOWN never exceeds NEAREST, which never exceeds UP")
    void policiesAreOrderedByOptimism() {
        // DOWN systematically understates a duration and so flatters the network, UP does
        // the opposite. That ordering is the reason a study must fix one policy across
        // every variant it compares.
        for (double periods : new double[] {0.1, 0.5, 0.9, 1.25, 1.5, 1.75, 3.3}) {
            long down = RoundingPolicy.DOWN.round(periods);
            long nearest = RoundingPolicy.NEAREST.round(periods);
            long up = RoundingPolicy.UP.round(periods);
            assertThat(down).as("DOWN <= NEAREST at %s", periods).isLessThanOrEqualTo(nearest);
            assertThat(nearest).as("NEAREST <= UP at %s", periods).isLessThanOrEqualTo(up);
        }
    }

    @Test
    @DisplayName("COLUMN_DEFINITION lists exactly the constants")
    void columnDefinitionMatchesTheEnum() {
        assertThat(RoundingPolicy.COLUMN_DEFINITION).isEqualTo("ENUM('NEAREST','UP','DOWN')");
        for (RoundingPolicy policy : RoundingPolicy.values()) {
            assertThat(RoundingPolicy.COLUMN_DEFINITION).contains("'" + policy.name() + "'");
        }
        assertThat(RoundingPolicy.values()).hasSize(3);
    }
}
