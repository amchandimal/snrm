package com.snrm.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The three recovery shapes, and the two boundaries all of them share.
 *
 * <p>The shared boundaries matter more than the shapes: three profiles that disagreed about the
 * depth of a disruption or about when it ends would make the choice of profile change what
 * {@code TTR} and {@code LOSS_AREA} measure, rather than how the network gets back.
 */
@DisplayName("RecoveryProfile")
class RecoveryProfileTest {

    private static final double TOLERANCE = 1e-12;

    private static final RecoveryProfile STEP = new StepRecoveryProfile();
    private static final RecoveryProfile LINEAR = new LinearRecoveryProfile();
    private static final RecoveryProfile EXPONENTIAL = new ExponentialRecoveryProfile();

    static Stream<RecoveryProfile> profiles() {
        return Stream.of(STEP, LINEAR, EXPONENTIAL);
    }

    @Nested
    @DisplayName("the boundaries every profile shares")
    class SharedBoundaries {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.snrm.scenario.RecoveryProfileTest#profiles")
        @DisplayName("at the moment of impact, availability is exactly 1 − severity")
        void impactIsTheSameForAll(RecoveryProfile profile) {
            assertThat(profile.availabilityAt(0, 5, 0.5)).isCloseTo(0.5, within(TOLERANCE));
            assertThat(profile.availabilityAt(0, 5, 1.0)).isCloseTo(0.0, within(TOLERANCE));
            assertThat(profile.availabilityAt(0, 5, 0.0)).isCloseTo(1.0, within(TOLERANCE));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.snrm.scenario.RecoveryProfileTest#profiles")
        @DisplayName("the window's end is exactly 1 — EXPONENTIAL's residual is closed, not trailed")
        void recoveryIsExact(RecoveryProfile profile) {
            assertThat(profile.availabilityAt(5, 5, 0.9)).isEqualTo(1.0);
            assertThat(profile.availabilityAt(500, 5, 0.9)).isEqualTo(1.0);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.snrm.scenario.RecoveryProfileTest#profiles")
        @DisplayName("before the event, and for a window that rounded to zero, availability is 1")
        void outsideTheWindow(RecoveryProfile profile) {
            assertThat(profile.availabilityAt(-1, 5, 0.9)).isEqualTo(1.0);
            // A duration finer than the network's period rounds to zero; the engine is
            // entitled to a number rather than a division by zero.
            assertThat(profile.availabilityAt(0, 0, 0.9)).isEqualTo(1.0);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.snrm.scenario.RecoveryProfileTest#profiles")
        @DisplayName("availability never leaves [0,1]")
        void staysInRange(RecoveryProfile profile) {
            for (long elapsed = 0; elapsed < 8; elapsed++) {
                assertThat(profile.availabilityAt(elapsed, 8, 1.0)).isBetween(0.0, 1.0);
                assertThat(profile.availabilityAt(elapsed, 8, 0.3)).isBetween(0.0, 1.0);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.snrm.scenario.RecoveryProfileTest#profiles")
        @DisplayName("recovery is monotone — capacity never goes back down inside the window")
        void monotone(RecoveryProfile profile) {
            double previous = -1;
            for (long elapsed = 0; elapsed < 10; elapsed++) {
                double available = profile.availabilityAt(elapsed, 10, 0.6);
                assertThat(available).as("elapsed %d", elapsed).isGreaterThanOrEqualTo(previous);
                previous = available;
            }
        }
    }

    @Nested
    @DisplayName("STEP — full capacity returns after duration")
    class Step {

        @Test
        @DisplayName("flat at 1 − severity for the whole window")
        void flatAcrossTheWindow() {
            for (long elapsed = 0; elapsed < 4; elapsed++) {
                assertThat(STEP.availabilityAt(elapsed, 4, 0.5))
                        .as("elapsed %d", elapsed)
                        .isCloseTo(0.5, within(TOLERANCE));
            }
            assertThat(STEP.availabilityAt(4, 4, 0.5)).isEqualTo(1.0);
        }

        /**
         * The rectangle that makes {@code docs/simulation-verification.md} hand-computable: over a
         * window of {@code w}, STEP loses {@code severity × w} capacity-periods.
         */
        @Test
        @DisplayName("integrated loss over the window is severity × window")
        void integratedLoss() {
            assertThat(lossOver(STEP, 6, 0.5)).isCloseTo(3.0, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("LINEAR — capacity ramps back over the recovery window")
    class Linear {

        @Test
        @DisplayName("a constant fraction of the loss is restored each period")
        void constantRepairRate() {
            // window 4, severity 0.8: the loss falls by 0.2 a period.
            assertThat(LINEAR.availabilityAt(0, 4, 0.8)).isCloseTo(0.2, within(TOLERANCE));
            assertThat(LINEAR.availabilityAt(1, 4, 0.8)).isCloseTo(0.4, within(TOLERANCE));
            assertThat(LINEAR.availabilityAt(2, 4, 0.8)).isCloseTo(0.6, within(TOLERANCE));
            assertThat(LINEAR.availabilityAt(3, 4, 0.8)).isCloseTo(0.8, within(TOLERANCE));
            assertThat(LINEAR.availabilityAt(4, 4, 0.8)).isEqualTo(1.0);
        }

        /**
         * The claim in {@link LinearRecoveryProfile}'s Javadoc: the loss summed over the window is
         * {@code severity × (w + 1) / 2}, a little over half of STEP's {@code severity × w}.
         *
         * <p>The sum runs over the periods <em>inside</em> the window and the first of them is at
         * full severity, which is where the {@code +1} comes from — the easy mistake is {@code −1},
         * and it would understate every LINEAR loss area by one severity-period.
         */
        @Test
        @DisplayName("integrated loss is severity × (window + 1) / 2 — a little over half of STEP's")
        void integratedLossIsAboutHalfOfStep() {
            assertThat(lossOver(LINEAR, 6, 0.5)).isCloseTo(0.5 * 7 / 2.0, within(TOLERANCE));
            assertThat(lossOver(LINEAR, 10, 0.4)).isCloseTo(0.4 * 11 / 2.0, within(TOLERANCE));
            assertThat(lossOver(LINEAR, 6, 0.5)).isLessThan(lossOver(STEP, 6, 0.5));
        }
    }

    @Nested
    @DisplayName("EXPONENTIAL — asymptotic ramp")
    class Exponential {

        @Test
        @DisplayName("k = 3 leaves about 5% of the loss outstanding at the window's end")
        void decayConstant() {
            // The last period *inside* a window of 10: elapsed 9, so e^(-3 × 0.9) = 0.0672.
            double outstanding = 1 - EXPONENTIAL.availabilityAt(9, 10, 1.0);
            assertThat(outstanding).isCloseTo(Math.exp(-2.7), within(1e-12));
            // What the constant is chosen for: at a full window, e^-3 ≈ 0.0498.
            assertThat(Math.exp(-ExponentialRecoveryProfile.DECAY_CONSTANTS))
                    .isCloseTo(0.0498, within(0.0005));
        }

        /**
         * The asymptotic shape, stated as the property that actually distinguishes it: the amount
         * recovered each period <em>shrinks</em>, where LINEAR's is constant by construction.
         *
         * <p>A pointwise "exponential is above/below linear" comparison would be the obvious test
         * and is not a true statement: the two cross near the end of a long window, so which one is
         * higher at the last period depends on the window's length.
         */
        @Test
        @DisplayName("the per-period gain shrinks, where LINEAR's is constant")
        void recoveryDecelerates() {
            double previousGain = Double.MAX_VALUE;
            double linearGain = -1;
            for (long elapsed = 1; elapsed < 10; elapsed++) {
                double gain = EXPONENTIAL.availabilityAt(elapsed, 10, 0.8)
                        - EXPONENTIAL.availabilityAt(elapsed - 1, 10, 0.8);
                assertThat(gain).as("exponential gain at %d", elapsed)
                        .isPositive()
                        .isLessThan(previousGain);
                previousGain = gain;

                double straight = LINEAR.availabilityAt(elapsed, 10, 0.8)
                        - LINEAR.availabilityAt(elapsed - 1, 10, 0.8);
                if (linearGain < 0) {
                    linearGain = straight;
                }
                assertThat(straight).as("linear gain at %d", elapsed)
                        .isCloseTo(linearGain, within(TOLERANCE));
            }
        }

        @Test
        @DisplayName("front-loaded: by the window's midpoint it has recovered more than LINEAR")
        void frontLoaded() {
            assertThat(EXPONENTIAL.availabilityAt(3, 6, 0.5))
                    .isGreaterThan(LINEAR.availabilityAt(3, 6, 0.5));
        }

        /** The ordering the class Javadoc claims: EXPONENTIAL is the mildest of the three. */
        @Test
        @DisplayName("loses less than LINEAR, which loses less than STEP")
        void integratedLoss() {
            assertThat(lossOver(EXPONENTIAL, 6, 0.5))
                    .isLessThan(lossOver(LINEAR, 6, 0.5));
            assertThat(lossOver(LINEAR, 6, 0.5)).isLessThan(lossOver(STEP, 6, 0.5));
        }
    }

    @Nested
    @DisplayName("RecoveryProfiles — the registry")
    class Registry {

        @Test
        @DisplayName("resolves every persisted enum constant to its strategy")
        void resolvesEveryConstant() {
            RecoveryProfiles profiles = new RecoveryProfiles(List.of(STEP, LINEAR, EXPONENTIAL));
            for (RecoveryProfileType type : RecoveryProfileType.values()) {
                assertThat(profiles.of(type).type()).isEqualTo(type);
            }
        }

        @Test
        @DisplayName("a constant with no bean is refused at startup, not mid-replication")
        void missingProfileIsAStartupFailure() {
            assertThatThrownBy(() -> new RecoveryProfiles(List.of(STEP)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LINEAR");
        }

        @Test
        @DisplayName("two beans claiming one profile are refused")
        void duplicateProfileIsRefused() {
            assertThatThrownBy(() -> new RecoveryProfiles(
                    List.of(STEP, new StepRecoveryProfile(), LINEAR, EXPONENTIAL)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STEP");
        }
    }

    /** Capacity-periods lost over a whole window — the area of the availability shortfall. */
    private static double lossOver(RecoveryProfile profile, long window, double severity) {
        double lost = 0;
        for (long elapsed = 0; elapsed < window; elapsed++) {
            lost += 1 - profile.availabilityAt(elapsed, window, severity);
        }
        return lost;
    }
}
