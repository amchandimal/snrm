package com.snrm.simulation;

import com.snrm.metrics.MetricResult;
import com.snrm.metrics.MetricResultRepository;
import com.snrm.metrics.MetricValue;
import com.snrm.network.Network;
import com.snrm.scenario.DisruptionScenario;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Every database write a running simulation job makes.
 *
 * <p>Separate from {@link SimulationService} for one reason: a job executes on a worker thread with
 * no transaction and no request context, and each of its writes has to open its own. A self-call
 * inside the service would bypass the proxy and run without one — the classic Spring mistake, and
 * one that would show up as a run stuck at {@code RUNNING} after a failure rather than as an error.
 * Every method here is a transaction boundary, and the job calls them from outside.
 *
 * <p>Each method is also <strong>independently short</strong>. A run holds no transaction open
 * across its replications: the Monte Carlo fan-out can take minutes, and a connection
 * held for the duration would be the one resource a bounded job pool did not bound.
 *
 * <p><strong>Terminal states are written even when the job failed.</strong> A run left at
 * {@code RUNNING} would freeze its network for ever under the immutability rule, with no way
 * back short of editing the database — so the failure and cancellation paths matter more than the
 * happy one, and {@link Propagation#REQUIRES_NEW} keeps them from being rolled back by whatever went
 * wrong.
 */
@Component
class SimulationRunWriter {

    private final SimulationRunRepository runs;
    private final RunTimeseriesRepository timeseries;
    private final RunNodeTimeseriesRepository nodeTimeseries;
    private final RunLinkTimeseriesRepository linkTimeseries;
    private final MetricResultRepository metrics;

    SimulationRunWriter(SimulationRunRepository runs, RunTimeseriesRepository timeseries,
            RunNodeTimeseriesRepository nodeTimeseries, RunLinkTimeseriesRepository linkTimeseries,
            MetricResultRepository metrics) {
        this.runs = runs;
        this.timeseries = timeseries;
        this.nodeTimeseries = nodeTimeseries;
        this.linkTimeseries = linkTimeseries;
        this.metrics = metrics;
    }

    // The class is package-private, so nothing outside the module can reach these; the methods are
    // public because Spring's CGLIB proxy has to be able to override every one it advises, and a
    // transactional method that is silently not advised is the failure this class exists to avoid.

    /**
     * Creates the {@code QUEUED} run row — <strong>committed before this method returns</strong>.
     *
     * <p>{@code REQUIRES_NEW} is the whole point, and it is about visibility rather than isolation.
     * The worker that will pick this run up reads it through a new transaction on a different
     * connection ({@link #markRunning}), and InnoDB will not show one transaction another's
     * uncommitted rows — so a run row merely <em>written</em> inside the submission's transaction is
     * invisible to a worker that wins the race against the commit, and the job dies on its first
     * line with "No simulation run with id N". The worker was racing the commit's fsync, and on a
     * quiet machine it wins often. Committing here, before {@code JobService.submit} is called,
     * closes the race by construction rather than by luck.
     *
     * <p>The freeze lands at this commit: from here the row is durable and
     * network-locking. The one path that has to compensate is the caller's — a submission the job
     * queue then refuses must {@link #discardNeverQueued} this row, or the network stays frozen for
     * a run no worker will ever execute.
     *
     * @param scenario the scenario applied, or null for a baseline run (FR-17)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long createQueued(Network network, DisruptionScenario scenario, String paramsJson) {
        // The two references come from the caller's (suspended) persistence context. That is fine
        // here and worth saying why: the association has no cascade, so persisting the run writes
        // their foreign keys from their ids and touches neither entity.
        return runs.save(new SimulationRun(network, scenario, paramsJson)).getId();
    }

    /**
     * Removes a run whose job was never accepted — the compensation for {@link #createQueued}.
     *
     * <p>Only the 429 path reaches this: the row committed, then {@code JobService} refused the
     * task because its bounded queue was full. The row is {@code QUEUED} and network-locking, and
     * nothing will ever move it, so deleting it is what releases the network. Guarded to the
     * never-started state so a mis-aimed call cannot take out a run a worker owns.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardNeverQueued(long runId) {
        runs.findById(runId)
                .filter(run -> run.getStatus() == SimulationStatus.QUEUED
                        && run.getStartedAt() == null)
                .ifPresent(runs::delete);
    }

    /** A worker has picked the run up. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(long runId) {
        SimulationRun run = require(runId);
        run.setStatus(SimulationStatus.RUNNING);
        run.setStartedAt(Instant.now());
    }

    /**
     * The run finished: its metric suite and its curves are written, and it becomes immutable.
     *
     * <p><strong>One transaction for every table and the status, and the status last.</strong> A
     * reader can never see a {@code DONE} run with half a metric suite, half a curve, or a network
     * series without its per-element one — which matters more now than it did with two
     * tables, because a client that found {@code DONE} and no element rows would correctly conclude
     * the run did not record them and would render an empty playback view for ever.
     *
     * <p>The volume is the one thing that changed. The suite is a dozen rows and the network curve
     * one row per period, but the element series is {@code horizon × (nodes + links)} — 52,000 on the
     * 1,000-node networks of FR-04. That is a batched insert rather than a big one:
     * {@code hibernate.jdbc.batch_size} is 50 with {@code order_inserts} on
     * ({@code application.properties}), the rows are {@link org.springframework.data.domain.Persistable}
     * with {@code isNew()} true so no {@code SELECT} precedes any {@code INSERT}, and each
     * {@code saveAll} is one table's worth so the batches never interleave.
     *
     * <p>The known ceiling is the persistence context, which holds all of those entities until this
     * transaction commits. At research scale — the samples under {@code samples/}, and the networks
     * one researcher builds by hand — it is a few thousand small rows and not worth
     * managing. If FR-04's thousand-node networks ever become the routine case, the fix is a
     * periodic {@code flush()}/{@code clear()} through an {@code EntityManager} rather than a
     * second transaction: the status must still be written last, in this one, or a reader could see
     * a {@code DONE} run whose element series is half there.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistResults(long runId, List<MetricValue> values, SimulationTraces traces) {
        SimulationRun run = require(runId);

        List<MetricResult> rows = new ArrayList<>(values.size());
        for (MetricValue value : values) {
            // network + run: a simulated metric belongs to the run that produced it, unlike a
            // topological one which belongs to the network.
            MetricResult row = new MetricResult(run.getNetwork(), run, value.code(), value.scope(),
                    value.scopeId(), value.value());
            row.setCiLow(value.ciLow());
            row.setCiHigh(value.ciHigh());
            row.setDisplayUnit(value.displayUnit());
            rows.add(row);
        }
        metrics.saveAll(rows);
        timeseries.saveAll(aggregate(run, traces));

        // Null when the run asked not to record (SimulationParams.recordElementTimeseries). No rows
        // at all is what the API reads as "unavailable" — deliberately not an empty series, which
        // would look like a network in which nothing ever moved.
        ElementSeries elements = traces.elements();
        if (elements != null) {
            nodeTimeseries.saveAll(nodeRows(run, elements));
            linkTimeseries.saveAll(linkRows(run, elements));
        }

        run.setStatus(SimulationStatus.DONE);
        run.setFinishedAt(Instant.now());
    }

    /** The job threw. The run keeps nothing and stops freezing its network. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long runId) {
        markTerminal(runId, SimulationStatus.FAILED);
    }

    /** The job was cancelled. Also releases the network. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelled(long runId) {
        markTerminal(runId, SimulationStatus.CANCELLED);
    }

    private void markTerminal(long runId, SimulationStatus status) {
        SimulationRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() == SimulationStatus.DONE) {
            // Missing means it was deleted with its project while the job ran; DONE means the job
            // failed after persisting its results, and a completed run is immutable.
            return;
        }
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
    }

    /**
     * Collapses the replication traces into the per-period rows of {@code RUN_TIMESERIES}.
     *
     * <blockquote>"stores per-period aggregates (served demand, total demand, cost) as replication
     * averages"</blockquote>
     *
     * <p>Means, not sums — a client comparing two runs with different replication counts must be
     * comparing like with like. Both curves are averaged: the disrupted set into
     * {@code served_demand} and {@code cost}, the paired baseline set into the two columns
     * {@code V6__run_timeseries_baseline.sql} added, which is what makes the resilience triangle
     * drawable from stored data.
     */
    private static List<RunTimeseries> aggregate(SimulationRun run, SimulationTraces traces) {
        int horizon = traces.horizonPeriods();
        int replications = traces.replications();
        List<RunTimeseries> rows = new ArrayList<>(horizon);
        if (replications == 0) {
            return rows;
        }
        // Empty on the baseline run of FR-17, where the pairing was skipped: the run itself is the
        // undisrupted curve, so the baseline columns take the run's own figures. Definitionally
        // true rather than a stand-in — the paired set, had it run, would have drawn identically —
        // and it keeps every reader of the two curves working, with a triangle of zero area.
        boolean paired = !traces.baseline().isEmpty();

        for (int t = 0; t < horizon; t++) {
            double totalDemand = 0;
            double served = 0;
            double cost = 0;
            double endingInventory = 0;
            double inPipeline = 0;
            double baselineServed = 0;
            double baselineCost = 0;
            for (int r = 0; r < replications; r++) {
                PeriodTrace disrupted = traces.disrupted().get(r).period(t);
                totalDemand += disrupted.totalDemand();
                served += disrupted.servedDemand();
                cost += disrupted.totalCost();
                // Both were already on the trace and were being discarded; V9 gives them columns.
                // They are the network totals the per-element on_hand and in_transit series sum to,
                // so a client can draw the stock and pipeline curves without pulling the
                // element series — and a reader can check one against the other.
                endingInventory += disrupted.endingInventory();
                inPipeline += disrupted.inPipeline();
                PeriodTrace baseline = paired ? traces.baseline().get(r).period(t) : disrupted;
                baselineServed += baseline.servedDemand();
                baselineCost += baseline.totalCost();
            }
            RunTimeseries row = new RunTimeseries(run, t);
            row.setTotalDemand(totalDemand / replications);
            row.setServedDemand(served / replications);
            row.setCost(cost / replications);
            row.setBaselineServedDemand(baselineServed / replications);
            row.setBaselineCost(baselineCost / replications);
            row.setEndingInventory(endingInventory / replications);
            row.setInPipeline(inPipeline / replications);
            rows.add(row);
        }
        return rows;
    }

    /**
     * The node half of the per-element series as rows (FR-18).
     *
     * <p>The means were taken in the engine, by {@link ElementAccumulator}; nothing is computed here.
     * The only translation is the sentinel: {@link ElementTrace#ABSENT} becomes a SQL {@code NULL},
     * because a period in which nothing was dispatched toward a node has no inbound lead and a 0
     * there would claim instantaneous delivery — absent renders absent, never zero.
     */
    private static List<RunNodeTimeseries> nodeRows(SimulationRun run, ElementSeries elements) {
        int horizon = elements.horizonPeriods();
        List<RunNodeTimeseries> rows =
                new ArrayList<>(elements.nodes().size() * horizon);
        for (ElementSeries.NodeSeries node : elements.nodes()) {
            for (int t = 0; t < horizon; t++) {
                RunNodeTimeseries row = new RunNodeTimeseries(run, node.nodeId(), t);
                row.setOnHand(node.onHand()[t]);
                row.setInTransit(node.inTransit()[t]);
                row.setArrivals(node.arrivals()[t]);
                row.setServed(node.served()[t]);
                row.setUnserved(node.unserved()[t]);
                row.setThroughput(node.throughput()[t]);
                row.setAvailability(node.availability()[t]);
                row.setInboundLead(boxed(node.inboundLead()[t]));
                row.setBaselineOnHand(node.baselineOnHand()[t]);
                row.setBaselineServed(node.baselineServed()[t]);
                rows.add(row);
            }
        }
        return rows;
    }

    /** The link half; {@code utilisation} takes the same absent-not-zero translation as above. */
    private static List<RunLinkTimeseries> linkRows(SimulationRun run, ElementSeries elements) {
        int horizon = elements.horizonPeriods();
        List<RunLinkTimeseries> rows = new ArrayList<>(elements.links().size() * horizon);
        for (ElementSeries.LinkSeries link : elements.links()) {
            for (int t = 0; t < horizon; t++) {
                RunLinkTimeseries row = new RunLinkTimeseries(run, link.linkId(), t);
                row.setFlow(link.flow()[t]);
                row.setUtilisation(boxed(link.utilisation()[t]));
                row.setAvailability(link.availability()[t]);
                row.setBaselineFlow(link.baselineFlow()[t]);
                rows.add(row);
            }
        }
        return rows;
    }

    /** {@link ElementTrace#ABSENT} on the way to a nullable column. */
    private static Double boxed(double value) {
        return Double.isNaN(value) ? null : value;
    }

    private SimulationRun require(long runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("No simulation run with id " + runId));
    }
}
