package com.snrm.metrics.simulated;

import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricValue;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import com.snrm.simulation.PeriodTrace;
import com.snrm.simulation.ReplicationTrace;
import com.snrm.simulation.SimulationTraces;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The eleven simulated calculators over hand-built traces — the edge cases
 * {@code docs/simulation-verification.md} cannot reach because its run is deliberately ordinary.
 *
 * <p>Building a {@link SimulationTraces} directly rather than running the engine is the point: each
 * test here states a shape of history and asserts what the definition says about it, so a calculator
 * cannot be verified against the engine that feeds it.
 */
@DisplayName("Simulated metric calculators")
class SimulatedCalculatorTest {

    private static final double TOLERANCE = 1e-9;
    private static final NetworkGraph GRAPH = GraphFixtures.simulationChain();

    // ------------------------------------------------------------------ trace builders

    /** One replication whose periods all have {@code demand} and serve {@code served[t]}. */
    private static ReplicationTrace replication(int index, boolean baseline, Integer onset,
            double demand, double[] served, double costPerPeriod) {
        List<PeriodTrace> periods = new ArrayList<>(served.length);
        for (int t = 0; t < served.length; t++) {
            periods.add(new PeriodTrace(t, demand, served[t], 0, 0, costPerPeriod, 0, 0, 0, 0,
                    onset != null && t >= onset));
        }
        return new ReplicationTrace(index, 1L, baseline,
                onset == null ? OptionalInt.empty() : OptionalInt.of(onset), periods);
    }

    private static MetricContext context(SimulationTraces traces) {
        return MetricContext.of(GRAPH, traces);
    }

    /** One disrupted replication and its perfect baseline twin. */
    private static SimulationTraces paired(Integer onset, double[] served, double disruptedCost,
            double baselineCost) {
        double[] perfect = new double[served.length];
        java.util.Arrays.fill(perfect, 50);
        return new SimulationTraces(
                List.of(replication(0, false, onset, 50, served, disruptedCost)),
                List.of(replication(0, true, null, 50, perfect, baselineCost)),
                served.length);
    }

    // ---------------------------------------------------------------------- the tests

    @Nested
    @DisplayName("FILL_RATE and SERVICE_LEVEL are different questions")
    class ServiceMetrics {

        /**
         * The case {@link ServiceLevelCalculator}'s Javadoc names: 2% missed in every period is a
         * fill rate of 0.98 and a service level of 0.
         */
        @Test
        @DisplayName("a chronic 2% shortfall: FILL_RATE 0.98, SERVICE_LEVEL 0")
        void chronicShortfall() {
            SimulationTraces traces = paired(0, new double[] {49, 49, 49, 49}, 0, 0);
            assertThat(only(new FillRateCalculator(), traces).value())
                    .isCloseTo(0.98, within(TOLERANCE));
            assertThat(only(new ServiceLevelCalculator(), traces).value())
                    .isCloseTo(0.0, within(TOLERANCE));
        }

        /** And the mirror image: one total failure among nine perfect periods. */
        @Test
        @DisplayName("one failed period in ten: FILL_RATE 0.9, SERVICE_LEVEL 0.9")
        void singleFailure() {
            double[] served = {50, 50, 50, 50, 50, 50, 50, 50, 50, 0};
            SimulationTraces traces = paired(9, served, 0, 0);
            assertThat(only(new FillRateCalculator(), traces).value())
                    .isCloseTo(0.9, within(TOLERANCE));
            assertThat(only(new ServiceLevelCalculator(), traces).value())
                    .isCloseTo(0.9, within(TOLERANCE));
        }

        @Test
        @DisplayName("FILL_RATE is a ratio of sums, not a mean of ratios")
        void ratioOfSums() {
            // A quiet period fully served and a busy one half served. Ratio of sums: 110/200 = 0.55.
            // Mean of ratios would be (1.0 + 0.5) / 2 = 0.75.
            List<PeriodTrace> periods = List.of(
                    new PeriodTrace(0, 20, 20, 0, 0, 0, 0, 0, 0, 0, false),
                    new PeriodTrace(1, 180, 90, 0, 0, 0, 0, 0, 0, 0, false));
            SimulationTraces traces = new SimulationTraces(
                    List.of(new ReplicationTrace(0, 1L, false, OptionalInt.empty(), periods)),
                    List.of(new ReplicationTrace(0, 1L, true, OptionalInt.empty(), periods)), 2);
            assertThat(only(new FillRateCalculator(), traces).value())
                    .isCloseTo(110.0 / 200.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("periods with no demand are excluded from SERVICE_LEVEL, not counted as met")
        void quietPeriodsAreExcluded() {
            List<PeriodTrace> periods = List.of(
                    new PeriodTrace(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false),     // no demand
                    new PeriodTrace(1, 50, 25, 0, 0, 0, 0, 0, 0, 0, true));   // half served
            SimulationTraces traces = new SimulationTraces(
                    List.of(new ReplicationTrace(0, 1L, false, OptionalInt.of(1), periods)),
                    List.of(new ReplicationTrace(0, 1L, true, OptionalInt.empty(), periods)), 2);
            // One period with demand, and it failed — not "one of two", which would be 0.5.
            assertThat(only(new ServiceLevelCalculator(), traces).value())
                    .isCloseTo(0.0, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("TTR")
    class TimeToRecovery {

        @Test
        @DisplayName("counts from onset to the first period that regains the baseline")
        void countsFromOnset() {
            SimulationTraces traces = paired(2,
                    new double[] {50, 50, 30, 30, 40, 50, 50}, 0, 0);
            assertThat(only(new TimeToRecoveryCalculator(), traces).value())
                    .isCloseTo(3, within(TOLERANCE));
        }

        @Test
        @DisplayName("a replication that never recovers is censored at the horizon, not dropped")
        void censoredAtHorizon() {
            SimulationTraces traces = paired(1, new double[] {50, 20, 20, 20, 20}, 0, 0);
            // Onset 1, never recovers within 5 periods: 5 − 1 = 4, a lower bound on the true value.
            assertThat(only(new TimeToRecoveryCalculator(), traces).value())
                    .isCloseTo(4, within(TOLERANCE));
        }

        @Test
        @DisplayName("a replication with no disruption contributes no observation")
        void undisruptedContributesNothing() {
            SimulationTraces traces = paired(null, new double[] {50, 50, 50}, 0, 0);
            assertThat(new TimeToRecoveryCalculator().compute(context(traces)))
                    .as("no disruption means nothing to recover from, which is not TTR = 0")
                    .isEmpty();
        }

        @Test
        @DisplayName("it is time-valued, so the registry attaches the network's period unit")
        void isTimeValued() {
            assertThat(new TimeToRecoveryCalculator().timeValued()).isTrue();
        }
    }

    @Nested
    @DisplayName("LOSS_AREA and RESILIENCE_INDEX")
    class RecoveryMetrics {

        @Test
        @DisplayName("LOSS_AREA sums only shortfalls, never credits an over-performing period")
        void onlyShortfallsCount() {
            // Period 3 serves more than the baseline's 50 — possible under demand noise. It must not
            // be netted against the real loss in period 1.
            SimulationTraces traces = paired(1, new double[] {50, 25, 50, 60}, 0, 0);
            assertThat(only(new LossAreaCalculator(), traces).value())
                    .isCloseTo(0.5, within(TOLERANCE));
        }

        @Test
        @DisplayName("RESILIENCE_INDEX is measured from onset to the end of the horizon")
        void windowRunsToTheHorizon() {
            SimulationTraces traces = paired(2, new double[] {50, 50, 25, 50, 50}, 0, 0);
            // Periods 2–4: (0.5 + 1 + 1) / 3 over a baseline of 1.
            assertThat(only(new ResilienceIndexCalculator(), traces).value())
                    .isCloseTo(2.5 / 3.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("an undisrupted replication scores 1 rather than being dropped")
        void undisruptedScoresOne() {
            SimulationTraces traces = paired(null, new double[] {50, 50, 50}, 0, 0);
            assertThat(only(new ResilienceIndexCalculator(), traces).value())
                    .isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("it is clamped into the (0–1) range the definition states")
        void clampedToRange() {
            SimulationTraces traces = paired(0, new double[] {60, 60, 60}, 0, 0);
            assertThat(only(new ResilienceIndexCalculator(), traces).value()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("the cost metrics")
    class CostMetrics {

        @Test
        @DisplayName("DISRUPTION_COST_DELTA is a paired difference")
        void pairedDifference() {
            SimulationTraces traces = paired(1, new double[] {50, 40}, 100, 60);
            // Two periods each: 200 disrupted against 120 baseline.
            assertThat(only(new DisruptionCostDeltaCalculator(), traces).value())
                    .isCloseTo(80, within(TOLERANCE));
        }

        @Test
        @DisplayName("CVAR_COST is the mean of the worst 5%, and carries no interval")
        void conditionalValueAtRisk() {
            List<ReplicationTrace> disrupted = new ArrayList<>();
            List<ReplicationTrace> baseline = new ArrayList<>();
            // 100 replications costing 1..100 per period over one period.
            for (int i = 0; i < 100; i++) {
                disrupted.add(replication(i, false, 0, 50, new double[] {50}, i + 1));
                baseline.add(replication(i, true, null, 50, new double[] {50}, 0));
            }
            SimulationTraces traces = new SimulationTraces(disrupted, baseline, 1);

            MetricValue cvar = only(new CvarCostCalculator(), traces);
            // The five most expensive: 96, 97, 98, 99, 100 → mean 98.
            assertThat(cvar.value()).isCloseTo(98, within(TOLERANCE));
            assertThat(cvar.ciLow()).isNull();
            assertThat(cvar.ciHigh()).isNull();

            // TOTAL_COST over the same set does carry one.
            MetricValue total = only(new TotalCostCalculator(), traces);
            assertThat(total.value()).isCloseTo(50.5, within(TOLERANCE));
            assertThat(total.ciLow()).isNotNull().satisfies(low ->
                    assertThat(low).isLessThan(total.value()));
            assertThat(total.ciHigh()).isNotNull();
        }
    }

    @Nested
    @DisplayName("the context guard")
    class ContextGuard {

        @Test
        @DisplayName("a simulated calculator run without traces fails with a sentence")
        void requiresTraces() {
            MetricContext topological = MetricContext.of(GRAPH);
            assertThat(topological.hasTraces()).isFalse();
            assertThatThrownBy(() -> new FillRateCalculator().compute(topological))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no simulation traces");
        }
    }

    @Nested
    @DisplayName("ReplicationStatistics")
    class Statistics {

        @Test
        @DisplayName("one replication publishes a mean and no interval")
        void noIntervalAtOne() {
            List<MetricValue> summary = ReplicationStatistics.summarise("X", new double[] {5});
            assertThat(summary).hasSize(1);
            assertThat(summary.get(0).value()).isEqualTo(5);
            assertThat(summary.get(0).ciLow()).isNull();
            assertThat(summary.get(0).ciHigh()).isNull();
        }

        @Test
        @DisplayName("the interval is mean ± 1.96 · s / √N")
        void normalApproximation() {
            double[] observations = {2, 4, 4, 4, 5, 5, 7, 9};
            MetricValue value = ReplicationStatistics.summarise("X", observations).get(0);
            double mean = 5;
            double sd = ReplicationStatistics.standardDeviation(observations, mean);
            double halfWidth = ReplicationStatistics.Z_95 * sd / Math.sqrt(observations.length);
            assertThat(value.value()).isCloseTo(mean, within(TOLERANCE));
            assertThat(value.ciLow()).isCloseTo(mean - halfWidth, within(TOLERANCE));
            assertThat(value.ciHigh()).isCloseTo(mean + halfWidth, within(TOLERANCE));
        }

        @Test
        @DisplayName("bounded metrics have their interval clamped as well as their mean")
        void boundsAreClamped() {
            MetricValue value = ReplicationStatistics
                    .summariseBounded("X", new double[] {0.99, 1.0, 1.0, 0.98}, 0, 1).get(0);
            assertThat(value.ciHigh()).isLessThanOrEqualTo(1.0);
            assertThat(value.ciLow()).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("no observations produces no value, which the definition permits")
        void emptyProducesNothing() {
            assertThat(ReplicationStatistics.summarise("X", new double[0])).isEmpty();
        }

        @Test
        @DisplayName("the CVaR tail is at least one observation, however small N is")
        void tailIsNeverEmpty() {
            assertThat(ReplicationStatistics.conditionalValueAtRisk(new double[] {7}, 0.95))
                    .isEqualTo(7);
            assertThat(ReplicationStatistics.conditionalValueAtRisk(new double[] {1, 2, 3}, 0.95))
                    .isEqualTo(3);
        }
    }

    private static MetricValue only(com.snrm.metrics.MetricCalculator calculator,
            SimulationTraces traces) {
        List<MetricValue> values = calculator.compute(context(traces));
        assertThat(values).as("%s produced no value", calculator.code()).hasSize(1);
        return values.get(0);
    }
}
