package com.snrm.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded randomness, and the reproducibility promise that rests on it.
 *
 * <p>The property being checked is the one {@link ReplicationRng} was written for: a draw is
 * addressed by {@code (seed, replication, stream, period)} rather than consumed from a sequence, so
 * adding a stochastic element, giving a network another link or reordering two loops cannot shift
 * every subsequent draw and quietly turn a stored seed into a different run.
 */
@DisplayName("ReplicationRng")
class ReplicationRngTest {

    @Test
    @DisplayName("the same address always yields the same numbers")
    void addressesAreStable() {
        ReplicationRng first = new ReplicationRng(20260802L, 7);
        ReplicationRng second = new ReplicationRng(20260802L, 7);
        assertThat(first.stream(RngStream.DEMAND, 3).nextDouble())
                .isEqualTo(second.stream(RngStream.DEMAND, 3).nextDouble());
    }

    @Test
    @DisplayName("a stream can be re-read from its address, in any order and any number of times")
    void streamsAreNotConsumed() {
        ReplicationRng rng = new ReplicationRng(1L, 0);
        double firstReading = rng.stream(RngStream.DEMAND, 5).nextDouble();
        rng.stream(RngStream.FAILURES, 0).nextDouble();      // an unrelated draw in between
        rng.stream(RngStream.DEMAND, 9).nextDouble();
        assertThat(rng.stream(RngStream.DEMAND, 5).nextDouble()).isEqualTo(firstReading);
    }

    @Test
    @DisplayName("the paired baseline replication sees the same demand and failure draws")
    void commonRandomNumbers() {
        // The baseline set passes the same replication index, which is the whole mechanism
        // behind LOSS_AREA and DISRUPTION_COST_DELTA being paired differences.
        ReplicationRng disrupted = new ReplicationRng(99L, 12);
        ReplicationRng baseline = new ReplicationRng(99L, 12);
        for (int period = 0; period < 5; period++) {
            assertThat(disrupted.stream(RngStream.DEMAND, period).nextDouble())
                    .as("demand, period %d", period)
                    .isEqualTo(baseline.stream(RngStream.DEMAND, period).nextDouble());
            assertThat(disrupted.stream(RngStream.FAILURES, period).nextDouble())
                    .as("failures, period %d", period)
                    .isEqualTo(baseline.stream(RngStream.FAILURES, period).nextDouble());
        }
    }

    @Test
    @DisplayName("neighbouring addresses are uncorrelated — replications 1 and 2 are not neighbours")
    void neighbouringAddressesDiverge() {
        Set<Double> seen = new HashSet<>();
        for (int replication = 0; replication < 200; replication++) {
            seen.add(new ReplicationRng(0L, replication).stream(RngStream.DEMAND, 0).nextDouble());
        }
        assertThat(seen).as("200 replications produce 200 distinct first draws").hasSize(200);
    }

    @Test
    @DisplayName("streams are independent of each other at the same address")
    void streamsAreIndependent() {
        ReplicationRng rng = new ReplicationRng(5L, 3);
        Set<Double> seen = new HashSet<>();
        for (RngStream stream : RngStream.values()) {
            seen.add(rng.stream(stream, 0).nextDouble());
        }
        assertThat(seen).hasSize(RngStream.values().length);
    }

    @Test
    @DisplayName("a different seed gives a different run")
    void seedMatters() {
        assertThat(new ReplicationRng(1L, 0).stream(RngStream.DEMAND, 0).nextDouble())
                .isNotEqualTo(new ReplicationRng(2L, 0).stream(RngStream.DEMAND, 0).nextDouble());
    }

    @Test
    @DisplayName("a coefficient of variation of 0 takes no draw and returns exactly 1")
    void deterministicDemand() {
        SplittableRandom random = new SplittableRandom(1);
        assertThat(ReplicationRng.demandFactor(random, 0)).isEqualTo(1.0);
        // The generator is untouched, which is what makes a deterministic run bit-identical to one
        // whose noise was simply never switched on.
        assertThat(random.nextLong()).isEqualTo(new SplittableRandom(1).nextLong());
    }

    @Test
    @DisplayName("demand noise has mean 1 and the stated coefficient of variation, and never negative")
    void demandNoiseDistribution() {
        SplittableRandom random = new SplittableRandom(42);
        int draws = 200_000;
        double cv = 0.2;
        double sum = 0;
        double sumSquares = 0;
        double minimum = Double.MAX_VALUE;
        for (int i = 0; i < draws; i++) {
            double factor = ReplicationRng.demandFactor(random, cv);
            sum += factor;
            sumSquares += factor * factor;
            minimum = Math.min(minimum, factor);
        }
        double mean = sum / draws;
        double sd = Math.sqrt(sumSquares / draws - mean * mean);
        assertThat(mean).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.01));
        assertThat(sd).isCloseTo(cv, org.assertj.core.api.Assertions.within(0.01));
        assertThat(minimum).as("demand is never negative — a customer does not supply the network")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("jitter is symmetric and bounded, and zero magnitude takes no draw")
    void jitterIsSymmetric() {
        assertThat(ReplicationRng.jitter(new SplittableRandom(1), 0)).isZero();

        SplittableRandom random = new SplittableRandom(3);
        long total = 0;
        int draws = 100_000;
        for (int i = 0; i < draws; i++) {
            long jitter = ReplicationRng.jitter(random, 2);
            assertThat(jitter).isBetween(-2L, 2L);
            total += jitter;
        }
        // Symmetric: an asymmetric draw would make "add timing uncertainty" quietly also mean
        // "make disruptions longer", which is a different modelling statement.
        assertThat((double) total / draws)
                .isCloseTo(0, org.assertj.core.api.Assertions.within(0.02));
    }
}
