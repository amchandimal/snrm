package com.snrm.simulation;

import com.snrm.common.JobCancelledException;
import com.snrm.common.ProgressSink;
import com.snrm.network.NetworkGraph;
import com.snrm.scenario.ScenarioPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Monte Carlo half: N disrupted replications and N undisrupted baseline ones, run in
 * parallel on virtual threads.
 *
 * <blockquote>"N replications (default 100) run in parallel on virtual threads; traces aggregate
 * into per-period means and metric CIs."</blockquote>
 *
 * <p><strong>Virtual threads, and why they are the right ones here.</strong> A replication is not
 * I/O-bound — it is a sequence of minimum-cost flow solves — so virtual threads buy no concurrency
 * over the hardware. What they buy is that {@code 2N} of them can be created without thought: the
 * default is 100 replications and far more are allowed, and a platform thread per replication would
 * be a pool-sizing decision inside the engine. The bounding that does matter happens one level up,
 * where {@code JobService} admits only a fixed number of <em>jobs</em>, so the machine is never
 * running three fan-outs at once.
 *
 * <p><strong>Nothing is shared but immutables.</strong> Every replication reads the same
 * {@link SimulationNetwork} and {@link ScenarioPlan} and writes only its own arrays, so there is no
 * lock anywhere in the loop. The two objects a finished replication publishes into —
 * {@code StreamingFigures} and {@link ElementAccumulator} — are both touched exactly once per
 * replication, thousands of flow solves apart, and they are touched on the same two statements so a
 * reader has one place to look for what a completion does. That is the whole return on the snapshot
 * pattern.
 *
 * <p><strong>The baseline set is paired, or skipped — never optional per event.</strong> Every
 * disrupted run automatically includes one undisrupted baseline replication set, because four of
 * the eleven simulated metrics are undefined without it.
 * Baseline replication <em>i</em> carries the same replication index as disrupted replication
 * <em>i</em>, which is what makes their demand and failure draws identical (see
 * {@link ReplicationRng}). But when the plan disrupts nothing at all — the baseline run of FR-17,
 * or a scenario whose events all have no effect — the two sets would be <em>the same replications
 * computed twice</em>, so the pairing is skipped and the run executes N rather than 2N
 * ({@link #runsPairedBaseline}). The four disruption-relative metrics then produce no rows, which
 * is expected, and skipping is what keeps a baseline run from costing double for
 * nothing.
 */
@Component
public class MonteCarloRunner {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloRunner.class);

    /**
     * Runs the whole replication set and returns the traces.
     *
     * @param graph    the immutable network snapshot
     * @param plan     the scenario, resolved against that network
     * @param params   the fully resolved parameter set recorded on the run
     * @param progress reported to as replications finish, and asked whether to stop
     * @throws JobCancelledException if cancellation was requested
     */
    public SimulationTraces run(NetworkGraph graph, ScenarioPlan plan, SimulationParams params,
            ProgressSink progress) {

        progress.report(0, "Preparing the network snapshot.");
        SimulationNetwork network = SimulationNetwork.of(graph);

        // The largest quantity the fixed-point grid has to carry: one period of demand plus the
        // whole network's order-up-to levels, with headroom. Coarsening rather than overflowing is
        // Quantiser's job; this is what it is told to fit.
        double maxQuantity = network.totalDemandPerPeriod() + network.totalSafetyStock()
                + totalReplenishTargets(network) + 1;
        Quantiser quantiser = Quantiser.of(params.quantum(), maxQuantity);
        if (quantiser.quantum() != params.quantum()) {
            log.warn("Fixed-point resolution coarsened from 1/{} to 1/{} of a unit: network {} "
                            + "carries quantities up to {} and the flow solver takes int "
                            + "capacities.",
                    params.quantum(), quantiser.quantum(), network.networkId(), maxQuantity);
        }

        SimulationEngine engine = new SimulationEngine(network, params, quantiser);
        ScenarioPlan undisrupted = plan.withoutEvents();

        boolean paired = runsPairedBaseline(plan, params);
        int replications = params.replications();
        int total = executedReplications(plan, params);
        StreamingFigures figures = new StreamingFigures(total);

        // The per-element series. One accumulator for the whole run: each replication
        // fills its own ElementTrace, folds it in on the statement below and drops it, so the memory
        // here is O(H × (N + E)) whatever the replication count is (see ElementAccumulator). Null
        // when the run asked not to record, in which case no element row is ever written and the
        // API reports the series as unavailable rather than as a network that did nothing.
        ElementAccumulator elements = params.recordElementTimeseries()
                ? ElementAccumulator.of(network, params.horizonPeriods())
                : null;

        List<Callable<Indexed>> tasks = new ArrayList<>(total);
        for (int i = 0; i < replications; i++) {
            int index = i;
            tasks.add(() -> {
                SimulationEngine.ReplicationOutcome outcome = engine.run(plan, index, false,
                        progress, elements == null ? null : elements.newTrace());
                if (elements != null) {
                    elements.foldDisrupted(outcome.elements());
                }
                publish(progress, figures.disruptedDone(outcome.trace()), total);
                return new Indexed(index, false, outcome.trace());
            });
            if (!paired) {
                continue;
            }
            tasks.add(() -> {
                // Same index, so this replication's demand and failure draws are the ones its
                // disrupted twin saw — the common random numbers.
                SimulationEngine.ReplicationOutcome outcome = engine.run(undisrupted, index, true,
                        progress, elements == null ? null : elements.newTrace());
                if (elements != null) {
                    elements.foldBaseline(outcome.elements());
                }
                publish(progress, figures.baselineDone(), total);
                return new Indexed(index, true, outcome.trace());
            });
        }

        progress.report(0, paired
                ? "Running %d replications (%d disrupted, %d baseline)."
                        .formatted(total, replications, replications)
                : "Running %d undisrupted replications.".formatted(total));
        long startedAt = System.nanoTime();

        ReplicationTrace[] disrupted = new ReplicationTrace[replications];
        ReplicationTrace[] baseline = paired ? new ReplicationTrace[replications]
                : new ReplicationTrace[0];

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Indexed>> futures = executor.invokeAll(tasks);
            for (Future<Indexed> future : futures) {
                Indexed result = future.get();
                if (result.baseline) {
                    baseline[result.index] = result.trace;
                } else {
                    disrupted[result.index] = result.trace;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JobCancelledException("Interrupted while running replications.");
        } catch (ExecutionException failure) {
            throw unwrap(failure);
        }

        // The fan-out returns whatever finished; a cancellation observed inside a replication
        // surfaces here rather than as a half-populated result set.
        progress.checkCancelled();

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Network {} scenario {}: {} replications over {} periods in {} ms",
                network.networkId(),
                plan.scenarioId() == null ? "none (baseline run)" : plan.scenarioId(),
                total, params.horizonPeriods(), elapsedMs);

        progress.report(1, "Replications complete.");
        // mean() after the join and after the cancellation check: every fold has happened-before it
        // through the accumulator's own lock, and a cancelled run never reaches it.
        return new SimulationTraces(Arrays.asList(disrupted), Arrays.asList(baseline),
                params.horizonPeriods(), elements == null ? null : elements.mean());
    }

    /**
     * Whether a run executes the paired undisrupted set, or skips it (FR-17).
     *
     * <p>The pairing exists to isolate a disruption's effect: two horizons sharing every random draw
     * and differing only in the events. A plan that disrupts nothing has no effect to isolate — the
     * two sets would be the same replications computed twice — so a baseline run, or a scenario
     * whose events all lack effect, runs N rather than 2N.
     *
     * <p>The one exception is deliberate: {@code baselineSuppressesFailures} makes the undisrupted
     * set drop the network's own random outages while the disrupted set keeps them (see
     * {@link SimulationParams}). Under that setting the two sets differ even with no events, so the
     * pairing still means something and is kept.
     *
     * <p>Static and public because {@code SimulationService} answers the 202 with the count this
     * decides, and two implementations of one rule is how the accepted DTO and the progress bar
     * disagree about what 100% is.
     */
    public static boolean runsPairedBaseline(ScenarioPlan plan, SimulationParams params) {
        if (!plan.isUndisrupted()) {
            return true;
        }
        return params.includeRandomFailures() && params.baselineSuppressesFailures();
    }

    /** How many replications the job will actually execute — {@code 2N} paired, {@code N} not. */
    public static int executedReplications(ScenarioPlan plan, SimulationParams params) {
        return runsPairedBaseline(plan, params) ? params.totalReplications()
                : params.replications();
    }

    /** One replication finished: move the bar, and put what the run knows so far on the poll. */
    private static void publish(ProgressSink progress, ProvisionalFigures figures, int total) {
        int done = figures.replicationsDone();
        progress.report((double) done / total, "Replication %d/%d.".formatted(done, total));
        progress.partial(figures);
    }

    /**
     * Re-throws what a replication threw.
     *
     * <p>A cancellation stays a cancellation: wrapping it in an {@link IllegalStateException} would
     * make {@code JobService} record a stopped run as {@code FAILED} and put a spurious message on
     * the poll.
     */
    private static RuntimeException unwrap(ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof JobCancelledException cancelled) {
            return cancelled;
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("A replication failed", cause == null ? failure : cause);
    }

    private static double totalReplenishTargets(SimulationNetwork network) {
        double total = 0;
        for (int i = 0; i < network.nodeCount(); i++) {
            double target = network.replenishTarget(i);
            // An unconstrained node's target is capped at its reachable demand, never at infinity —
            // but a defensive skip costs nothing and keeps a NaN out of the quantiser.
            if (Double.isFinite(target)) {
                total += target;
            }
        }
        return total;
    }

    /** One finished replication, tagged so the two index-paired lists can be filled in any order. */
    private record Indexed(int index, boolean baseline, ReplicationTrace trace) {
    }

    /**
     * The running statistics behind {@link ProvisionalFigures} (FR-17).
     *
     * <p><strong>Streaming, and only over what already exists.</strong> Each completed replication
     * contributes three additions taken from the trace the worker is already holding; nothing is
     * recomputed, nothing needs the whole set, and the metric suite is not touched. The figures
     * mirror their calculators' definitions — mean of per-replication {@code fillRate()} ratios,
     * mean worst demand-carrying period, mean {@code totalCost()} — so the number a researcher
     * watches does not jump when the persisted value replaces it.
     *
     * <p><strong>Disrupted replications only</strong> feed the three metric-shaped figures: the
     * suite these preview is computed over the disrupted set. Baseline completions still advance
     * {@code replicationsDone}, because the progress bar counts work, not meaning.
     *
     * <p>Synchronised rather than lock-free: completions arrive from every virtual thread, but at
     * one lock acquisition per <em>replication</em> — thousands of flow solves apart — contention
     * is not a number worth designing against, and a handful of {@code double}s under one lock is
     * easier to see to be consistent than four atomics that are not.
     */
    private static final class StreamingFigures {

        private final int total;
        private int done;
        private int disruptedDone;
        private double fillRateSum;
        private double minFillSum;
        private int minFillCount;
        private double costSum;

        StreamingFigures(int total) {
            this.total = total;
        }

        /** A disrupted replication finished: fold its trace in and snapshot. */
        synchronized ProvisionalFigures disruptedDone(ReplicationTrace trace) {
            done++;
            disruptedDone++;
            fillRateSum += trace.fillRate();
            costSum += trace.totalCost();
            double worst = worstDemandCarryingPeriod(trace);
            if (Double.isFinite(worst)) {
                minFillSum += worst;
                minFillCount++;
            }
            return snapshot();
        }

        /** A baseline replication finished: it advances the count and moves no figure. */
        synchronized ProvisionalFigures baselineDone() {
            done++;
            return snapshot();
        }

        private ProvisionalFigures snapshot() {
            return new ProvisionalFigures(done, total,
                    disruptedDone == 0 ? null : fillRateSum / disruptedDone,
                    minFillCount == 0 ? null : minFillSum / minFillCount,
                    disruptedDone == 0 ? null : costSum / disruptedDone);
        }

        /**
         * The replication's worst fill rate over periods that had demand — the same reading
         * {@code MinFillRateCalculator} takes, including its exclusion of demandless periods.
         * Infinite when no period carried demand, in which case the replication contributes
         * nothing, exactly as it contributes nothing to the final metric.
         */
        private static double worstDemandCarryingPeriod(ReplicationTrace trace) {
            double worst = Double.POSITIVE_INFINITY;
            for (PeriodTrace period : trace.periods()) {
                if (period.hasDemand()) {
                    worst = Math.min(worst, period.fillRate());
                }
            }
            return worst;
        }
    }
}
