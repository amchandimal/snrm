package com.snrm.simulation;

import com.snrm.common.ProgressSink;
import com.snrm.common.TimeUnit;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricScope;
import com.snrm.metrics.MetricValue;
import com.snrm.metrics.simulated.AvgInventoryCalculator;
import com.snrm.metrics.simulated.AvgPipelineCalculator;
import com.snrm.metrics.simulated.CvarCostCalculator;
import com.snrm.metrics.simulated.DisruptionCostDeltaCalculator;
import com.snrm.metrics.simulated.FillRateCalculator;
import com.snrm.metrics.simulated.LossAreaCalculator;
import com.snrm.metrics.simulated.MinFillRateCalculator;
import com.snrm.metrics.simulated.ResilienceIndexCalculator;
import com.snrm.metrics.simulated.ServiceLevelCalculator;
import com.snrm.metrics.simulated.TimeToRecoveryCalculator;
import com.snrm.metrics.simulated.TotalCostCalculator;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import com.snrm.scenario.DisruptionTargetType;
import com.snrm.scenario.PlannedEvent;
import com.snrm.scenario.ScenarioPlan;
import com.snrm.scenario.StepRecoveryProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Asserts every number in {@code docs/simulation-verification.md}, value by value.
 *
 * <p><strong>That document is the specification, and this test transcribes it.</strong> Each
 * assertion names the section that derives its figure, so the risk of metric definitions drifting
 * from literature definitions is caught by a test rather than by a reader. When the model changes,
 * <em>change the document first</em>: re-deriving these numbers from the new code would turn the
 * test into a record of what the code does, which is the one thing it must not be.
 *
 * <p>Runs with no Spring context, no database and no Docker — the whole point of the snapshot
 * isolation (see {@code GraphFixtures}).
 */
@DisplayName("docs/simulation-verification.md — the 3-node chain")
class SimulationVerificationTest {

    /**
     * §8: "compare to a tolerance — 1 × 10⁻⁹ is far tighter than any of these computations can
     * drift and far looser than the last-bit differences that summation order produces."
     */
    private static final double TOLERANCE = 1e-9;

    private static NetworkGraph graph;
    private static SimulationTraces traces;
    private static MetricContext context;

    @BeforeAll
    static void runTheVerificationScenario() {
        graph = GraphFixtures.simulationChain();

        // §1.4: one NODE event on PLANT-1, offset 3 periods, duration 3, severity 0.5, STEP,
        // probability 1.0. PLANT-1 is the second node the fixture declares, so its id is 2.
        ScenarioPlan plan = new ScenarioPlan(1L, "Plant outage", graph.networkId(),
                List.of(new PlannedEvent(1L, "NODE 2 (PLANT-1)", DisruptionTargetType.NODE,
                        Set.of(2L), Set.of(), 3, 3, 0.5, new StepRecoveryProfile(), 1.0)),
                List.of());

        // §1.5: one replication, fixed seed, no noise, no jitter, replenishment off.
        SimulationParams params = new SimulationParams(1, 20260802L, 10, 0, 0, true, false, 0,
                null, 1000, true, SimulationParams.ENGINE_VERSION);

        traces = new MonteCarloRunner().run(graph, plan, params, ProgressSink.none());
        context = MetricContext.of(graph, traces);
    }

    @Nested
    @DisplayName("§4 — the per-period table")
    class PerPeriodTable {

        /** §4.3, the disrupted run: served demand and total cost, period by period. */
        @ParameterizedTest(name = "t={0}: served {1}, cost {2}")
        @CsvSource({
                "0, 50, 375",
                "1, 50, 350",
                "2, 50, 650",
                "3, 40, 780",
                "4, 40, 780",
                "5, 40, 780",
                "6, 50, 650",
                "7, 50, 650",
                "8, 50, 650",
                "9, 50, 650"
        })
        void disruptedRun(int period, double served, double cost) {
            PeriodTrace trace = traces.disrupted().get(0).period(period);
            assertThat(trace.totalDemand()).isCloseTo(50, within(TOLERANCE));
            assertThat(trace.servedDemand()).isCloseTo(served, within(TOLERANCE));
            assertThat(trace.totalCost()).isCloseTo(cost, within(TOLERANCE));
        }

        /** §4.4, the baseline run: periods 3–5 look exactly like period 2. */
        @ParameterizedTest(name = "t={0}: served 50, cost {1}")
        @CsvSource({
                "0, 375", "1, 350", "2, 650", "3, 650", "4, 650",
                "5, 650", "6, 650", "7, 650", "8, 650", "9, 650"
        })
        void baselineRun(int period, double cost) {
            PeriodTrace trace = traces.baseline().get(0).period(period);
            assertThat(trace.servedDemand()).isCloseTo(50, within(TOLERANCE));
            assertThat(trace.totalCost()).isCloseTo(cost, within(TOLERANCE));
            assertThat(trace.disrupted()).isFalse();
        }

        /** §4.3: PLANT-1 opens with 100 units and drains over the first two periods. */
        @Test
        @DisplayName("the stock column drains 100 → 50 → 0 and stays there")
        void inventoryDrains() {
            ReplicationTrace run = traces.disrupted().get(0);
            assertThat(run.period(0).endingInventory()).isCloseTo(50, within(TOLERANCE));
            assertThat(run.period(1).endingInventory()).isCloseTo(0, within(TOLERANCE));
            for (int t = 2; t < 10; t++) {
                assertThat(run.period(t).endingInventory())
                        .as("period %d", t)
                        .isCloseTo(0, within(TOLERANCE));
            }
        }

        /**
         * §7.1 and §7.2.1: both lead times are 0 and no node has a processing dwell, so nothing is
         * ever in flight — in either run.
         *
         * <p>This is the column {@code AVG_PIPELINE} averages (§6.6), and §10 item 17 names a
         * non-zero here as a defect in the pipeline routing rather than a difference of opinion.
         */
        @Test
        @DisplayName("nothing is ever in the pipeline, in either run")
        void pipelineIsEmpty() {
            for (int t = 0; t < 10; t++) {
                assertThat(traces.disrupted().get(0).period(t).inPipeline())
                        .as("disrupted period %d", t)
                        .isCloseTo(0, within(TOLERANCE));
                assertThat(traces.baseline().get(0).period(t).inPipeline())
                        .as("baseline period %d", t)
                        .isCloseTo(0, within(TOLERANCE));
            }
        }

        /** §4.3, period 0: served from stock, so nothing crosses SUP-1's or PLANT-1's cost arc. */
        @Test
        @DisplayName("period 0 costs 300 fixed + 50 transport + 25 holding, and no variable cost")
        void periodZeroComponents() {
            PeriodTrace trace = traces.disrupted().get(0).period(0);
            assertThat(trace.fixedCost()).isCloseTo(300, within(TOLERANCE));
            assertThat(trace.variableCost()).isCloseTo(0, within(TOLERANCE));
            assertThat(trace.transportCost()).isCloseTo(50, within(TOLERANCE));
            assertThat(trace.holdingCost()).isCloseTo(25, within(TOLERANCE));
            assertThat(trace.shortageCost()).isCloseTo(0, within(TOLERANCE));
        }

        /** §4.3, period 3: 40 produced, 10 unserved at the product's unit value of 20. */
        @Test
        @DisplayName("period 3 costs 300 fixed + 200 variable + 80 transport + 200 shortage")
        void disruptedPeriodComponents() {
            PeriodTrace trace = traces.disrupted().get(0).period(3);
            assertThat(trace.fixedCost()).isCloseTo(300, within(TOLERANCE));
            assertThat(trace.variableCost()).isCloseTo(200, within(TOLERANCE));
            assertThat(trace.transportCost()).isCloseTo(80, within(TOLERANCE));
            assertThat(trace.holdingCost()).isCloseTo(0, within(TOLERANCE));
            assertThat(trace.shortageCost()).isCloseTo(200, within(TOLERANCE));
            assertThat(trace.unservedDemand()).isCloseTo(10, within(TOLERANCE));
            assertThat(trace.disrupted()).isTrue();
        }

        /** §4.1: the STEP window covers periods 3, 4 and 5 — and period 6 is already recovered. */
        @Test
        @DisplayName("exactly periods 3, 4 and 5 are flagged as disrupted")
        void disruptionWindow() {
            ReplicationTrace run = traces.disrupted().get(0);
            for (int t = 0; t < 10; t++) {
                assertThat(run.period(t).disrupted())
                        .as("period %d", t)
                        .isEqualTo(t >= 3 && t <= 5);
            }
            assertThat(run.onsetPeriod()).hasValue(3);
        }

        /** §4.3 and §4.4: the two totals the cost metrics are built from. */
        @Test
        @DisplayName("horizon totals are 6315 disrupted and 5925 baseline")
        void horizonTotals() {
            assertThat(traces.disrupted().get(0).totalCost()).isCloseTo(6315, within(TOLERANCE));
            assertThat(traces.baseline().get(0).totalCost()).isCloseTo(5925, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("§8 — the values the API must return")
    class MetricSuite {

        /** §6.1: 470/500, a ratio of sums. */
        @Test
        void fillRate() {
            assertThat(only(new FillRateCalculator()).value()).isCloseTo(0.94, within(TOLERANCE));
        }

        /** §6.2: 7 of 10 periods served in full — deliberately not the same number as FILL_RATE. */
        @Test
        void serviceLevel() {
            assertThat(only(new ServiceLevelCalculator()).value()).isCloseTo(0.7, within(TOLERANCE));
        }

        /** §6.3: the worst period with demand. */
        @Test
        void minFillRate() {
            assertThat(only(new MinFillRateCalculator()).value()).isCloseTo(0.8, within(TOLERANCE));
        }

        /** §5.1: onset 3, recovered at 6, so 3 periods — and it is reported in DAY. */
        @Test
        void timeToRecovery() {
            MetricValue ttr = only(new TimeToRecoveryCalculator());
            assertThat(ttr.value()).isCloseTo(3, within(TOLERANCE));
            // The calculator declares timeValued(); the registry is what attaches the unit,
            // so here we assert the network's clock is the DAY the document states.
            assertThat(graph.timeBasis().periodUnit()).isEqualTo(TimeUnit.DAY);
            assertThat(new TimeToRecoveryCalculator().timeValued()).isTrue();
        }

        /** §5.2: three periods at a shortfall of 0.2, in fill-rate·periods. */
        @Test
        void lossArea() {
            assertThat(only(new LossAreaCalculator()).value()).isCloseTo(0.6, within(TOLERANCE));
        }

        /** §5.3: 6.4/7 over periods 3–9. */
        @Test
        void resilienceIndex() {
            assertThat(only(new ResilienceIndexCalculator()).value())
                    .isCloseTo(32.0 / 35.0, within(TOLERANCE));
        }

        /** §6.4. */
        @Test
        void totalCost() {
            assertThat(only(new TotalCostCalculator()).value()).isCloseTo(6315, within(TOLERANCE));
        }

        /** §6.4, checked a second way in the document: 3 × (200 − 70). */
        @Test
        void disruptionCostDelta() {
            assertThat(only(new DisruptionCostDeltaCalculator()).value())
                    .isCloseTo(390, within(TOLERANCE));
        }

        /** §6.5: at N = 1 the worst 5% is that replication, so this equals TOTAL_COST. */
        @Test
        void cvarCost() {
            assertThat(only(new CvarCostCalculator()).value()).isCloseTo(6315, within(TOLERANCE));
        }

        /** §6.6: PLANT-1's 50 units in period 0 and nothing anywhere else, over ten periods. */
        @Test
        void avgInventory() {
            assertThat(only(new AvgInventoryCalculator()).value()).isCloseTo(5.0, within(TOLERANCE));
        }

        /**
         * §6.6: both lead times are 0, so nothing is ever in flight.
         *
         * <p><strong>A row of 0.0, not an absent row.</strong> A zero pipeline is a measurement this
         * network's design produces (§7.2.1); the four disruption-relative metrics are the undefined
         * case, and {@link #everyMetricProducesAValue} is where that distinction is asserted.
         */
        @Test
        void avgPipeline() {
            assertThat(only(new AvgPipelineCalculator()).value()).isCloseTo(0.0, within(TOLERANCE));
        }

        /**
         * §6.6: the inventory pair declines to rank, and the comparison view reads that from here.
         */
        @Test
        @DisplayName("AVG_INVENTORY and AVG_PIPELINE are NEUTRAL and not time-valued")
        void theInventoryPairIsNeutral() {
            assertThat(new AvgInventoryCalculator().direction()).isEqualTo(MetricDirection.NEUTRAL);
            assertThat(new AvgPipelineCalculator().direction()).isEqualTo(MetricDirection.NEUTRAL);
            assertThat(new AvgInventoryCalculator().timeValued()).isFalse();
            assertThat(new AvgPipelineCalculator().timeValued()).isFalse();
        }

        /**
         * §8: "Every {@code ciLow} and {@code ciHigh} is null, because a single replication has no
         * sample variance and reporting [x, x] would state a certainty that does not exist."
         */
        @Test
        @DisplayName("no confidence interval is published at one replication")
        void noIntervalsAtOneReplication() {
            List<MetricValue> all = List.of(
                    only(new FillRateCalculator()), only(new ServiceLevelCalculator()),
                    only(new MinFillRateCalculator()), only(new TimeToRecoveryCalculator()),
                    only(new LossAreaCalculator()), only(new ResilienceIndexCalculator()),
                    only(new TotalCostCalculator()), only(new DisruptionCostDeltaCalculator()),
                    only(new CvarCostCalculator()), only(new AvgInventoryCalculator()),
                    only(new AvgPipelineCalculator()));
            assertThat(all).allSatisfy(value -> {
                assertThat(value.ciLow()).as("%s ciLow", value.code()).isNull();
                assertThat(value.ciHigh()).as("%s ciHigh", value.code()).isNull();
                assertThat(value.scope()).as("%s scope", value.code()).isEqualTo(MetricScope.NETWORK);
            });
        }

        /** §8: eleven rows, and every one of them produced. None of the suite is undefined here. */
        @Test
        @DisplayName("all eleven simulated metrics produce a value")
        void everyMetricProducesAValue() {
            assertThat(List.of(
                    new FillRateCalculator().compute(context),
                    new ServiceLevelCalculator().compute(context),
                    new MinFillRateCalculator().compute(context),
                    new TimeToRecoveryCalculator().compute(context),
                    new LossAreaCalculator().compute(context),
                    new ResilienceIndexCalculator().compute(context),
                    new TotalCostCalculator().compute(context),
                    new DisruptionCostDeltaCalculator().compute(context),
                    new CvarCostCalculator().compute(context),
                    // AVG_PIPELINE included deliberately: its value here is 0.0 and it must still be
                    // one row. A calculator that returned List.of() for an all-zero series would
                    // pass every value assertion above and make a measurement look like an absence
                    // (§6.6).
                    new AvgInventoryCalculator().compute(context),
                    new AvgPipelineCalculator().compute(context)))
                    .allSatisfy(values -> assertThat(values).hasSize(1));
        }
    }

    /** The single network-scoped value a calculator produces on this run. */
    private static MetricValue only(com.snrm.metrics.MetricCalculator calculator) {
        List<MetricValue> values = calculator.compute(context);
        assertThat(values).as("%s produced no value", calculator.code()).hasSize(1);
        return values.get(0);
    }
}
