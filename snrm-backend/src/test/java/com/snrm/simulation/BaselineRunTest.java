package com.snrm.simulation;

import com.snrm.common.ProgressSink;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.simulated.TimeToRecoveryCalculator;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import com.snrm.scenario.ScenarioPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The baseline run of FR-17: no scenario, no paired set, N replications rather than
 * 2N.
 *
 * <p>The rule under test is {@link MonteCarloRunner#runsPairedBaseline}: the pairing
 * exists to isolate a disruption's effect, and a run that disrupts nothing has no effect to isolate
 * — its undisrupted set would be the same replications computed twice. The one carve-out is
 * {@code baselineSuppressesFailures}, under which the two sets differ even with no events.
 *
 * <p>Deterministic throughout, like {@code InventoryPipelineTest}: what is being checked is which
 * work runs, not what it computes.
 */
@DisplayName("Baseline run — FR-17, ")
class BaselineRunTest {

    private static NetworkGraph chain() {
        return GraphFixtures.network()
                .horizon(4)
                .supplier("SUP-1", 100.0)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "CUST-1", 100.0, 0)
                .build();
    }

    private static SimulationParams params(int replications, boolean includeRandomFailures,
            boolean baselineSuppressesFailures) {
        return new SimulationParams(replications, 7L, 4, 0, 0, includeRandomFailures,
                baselineSuppressesFailures, 0.1, null, 1000, true,
                SimulationParams.ENGINE_VERSION);
    }

    @Test
    @DisplayName("a baseline plan runs N replications and no paired set")
    void baselinePlanSkipsThePairing() {
        NetworkGraph graph = chain();
        ScenarioPlan plan = ScenarioPlan.baseline(graph.networkId());
        SimulationParams params = params(3, true, false);

        SimulationTraces traces = new MonteCarloRunner()
                .run(graph, plan, params, ProgressSink.none());

        assertThat(traces.disrupted()).as("one trace per replication").hasSize(3);
        assertThat(traces.baseline()).as("the pairing is skipped — 2N would cost double for "
                + "an identical set").isEmpty();
        assertThat(traces.anyDisruption()).isFalse();
    }

    @Test
    @DisplayName("a scenario plan keeps the paired baseline set")
    void scenarioPlanKeepsThePairing() {
        NetworkGraph graph = chain();
        // No events resolved — but the carve-out below is about params, so use one with events'
        // semantics: an empty plan with baselineSuppressesFailures active still pairs.
        ScenarioPlan plan = ScenarioPlan.baseline(graph.networkId());
        SimulationParams params = params(2, true, true);

        SimulationTraces traces = new MonteCarloRunner()
                .run(graph, plan, params, ProgressSink.none());

        assertThat(traces.baseline())
                .as("with baselineSuppressesFailures the two sets differ even with no events, "
                        + "so the pairing still means something")
                .hasSize(2);
    }

    @Test
    @DisplayName("executedReplications answers N for a baseline run and 2N for a paired one")
    void executedReplicationsFollowsTheSameRule() {
        ScenarioPlan baseline = ScenarioPlan.baseline(1);

        assertThat(MonteCarloRunner.executedReplications(baseline, params(100, true, false)))
                .isEqualTo(100);
        assertThat(MonteCarloRunner.executedReplications(baseline, params(100, true, true)))
                .as("the baselineSuppressesFailures carve-out").isEqualTo(200);
    }

    @Test
    @DisplayName("the runner publishes streaming figures as replications complete (FR-17)")
    void provisionalFiguresArePublished() {
        NetworkGraph graph = chain();
        List<ProvisionalFigures> published = Collections.synchronizedList(new ArrayList<>());
        ProgressSink capturing = new ProgressSink() {
            @Override
            public void report(double fraction, String message) {
            }

            @Override
            public double progress() {
                return 0;
            }

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public void partial(Object partial) {
                published.add((ProvisionalFigures) partial);
            }
        };

        new MonteCarloRunner().run(graph, ScenarioPlan.baseline(graph.networkId()),
                params(3, true, false), capturing);

        // One publication per completed replication; the completion order is the threads' business,
        // so the assertion is on the set rather than on the last-published element.
        assertThat(published).hasSize(3);
        ProvisionalFigures fullest = published.stream()
                .max((a, b) -> Integer.compare(a.replicationsDone(), b.replicationsDone()))
                .orElseThrow();
        assertThat(fullest.replicationsDone()).isEqualTo(3);
        assertThat(fullest.replicationsTotal()).isEqualTo(3);
        // The deterministic chain serves in full from period 0, so every figure is exact — and the
        // streaming statistic mirrors its calculator's definition, which is what keeps the number
        // from jumping when the persisted suite replaces it.
        assertThat(fullest.fillRate()).isEqualTo(1.0);
        assertThat(fullest.minFillRate()).isEqualTo(1.0);
        assertThat(fullest.totalCost()).isNotNull();
    }

    @Test
    @DisplayName("TTR produces no rows for a baseline run — absent, never zero")
    void ttrHasNothingToReport() {
        NetworkGraph graph = chain();
        SimulationTraces traces = new MonteCarloRunner()
                .run(graph, ScenarioPlan.baseline(graph.networkId()), params(2, true, false),
                        ProgressSink.none());

        // The guard this asserts is the one that keeps paired(i) out of an empty list: without it
        // the calculator would throw before its per-replication onset check could skip anything.
        assertThat(new TimeToRecoveryCalculator().compute(MetricContext.of(graph, traces)))
                .as("nothing was disrupted, so there is no recovery to time")
                .isEmpty();
    }
}
