package com.snrm.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snrm.common.JobCancelledException;
import com.snrm.common.JobHandle;
import com.snrm.common.DurationDto;
import com.snrm.common.JobService;
import com.snrm.common.ProgressSink;
import com.snrm.common.TimeBasis;
import com.snrm.metrics.MetricCalculatorRegistry;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricResult;
import com.snrm.metrics.MetricResultDto;
import com.snrm.metrics.MetricResultMapper;
import com.snrm.metrics.MetricResultRepository;
import com.snrm.metrics.MetricValue;
import com.snrm.network.Link;
import com.snrm.network.LinkRepository;
import com.snrm.network.Network;
import com.snrm.network.NetworkGraph;
import com.snrm.network.NetworkGraphFactory;
import com.snrm.network.NetworkLookup;
import com.snrm.network.Node;
import com.snrm.network.NodeRepository;
import com.snrm.scenario.DisruptionScenario;
import com.snrm.scenario.DisruptionScenarioRepository;
import com.snrm.scenario.ScenarioPlan;
import com.snrm.scenario.ScenarioPlanFactory;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The simulation module's persistence and API edge: validates a submission, freezes the network,
 * queues the job, and writes what it produced.
 *
 * <p>This class is the only one in {@code com.snrm.simulation} that touches a repository beside
 * {@link SimulationRunWriter}. Either side of it, the engine sees the immutable {@code NetworkGraph}
 * snapshot and the {@code ScenarioPlan} and returns value objects — which is what will let
 * the Phase 2 search call {@link MonteCarloRunner} directly to score a candidate it will
 * never persist.
 *
 * <h2>Everything is checked before the 202</h2>
 *
 * <p>{@code POST /simulations} answers "202 Accepted {jobId} (validated, queued)", and
 * the word that does the work is <em>validated</em>. A job that fails on its first line because a
 * scenario named another project's network is a 202 followed by a {@code FAILED} poll, where the
 * caller could have had a 404 with a sentence. So the whole of the work below happens inside the
 * request:
 *
 * <ol>
 *   <li>the network and the scenario are resolved and owned, and belong to the same project;</li>
 *   <li>every event's window fits the network's horizon — re-checked here even though
 *       {@code DisruptionScenarioService} checked it at write time, because it checked against
 *       whichever network the researcher was looking at and this run may be against a variant with a
 *       different clock;</li>
 *   <li>every event resolves to something in <em>this</em> network, or the submission is refused
 *       ({@link UnresolvedEventException});</li>
 *   <li>the network has demand to serve ({@link NoDemandException});</li>
 *   <li>the snapshot and the plan are built, inside the request's read transaction.</li>
 * </ol>
 *
 * <p><strong>Then the run row is written and committed, and that is what freezes the network.</strong>
 * {@code SimulationStatus.QUEUED} is network-locking, so from the moment this method
 * returns, an edit to the network is refused with {@code NETWORK_IMMUTABLE} and must fork a variant.
 * The order matters twice over. The row exists before the job is queued, so a job can never start
 * against a network that is still editable — and it is <em>committed</em> before the job is queued
 * ({@code SimulationRunWriter.createQueued}, {@code REQUIRES_NEW}), because the worker reads it
 * through a transaction of its own and cannot see this method's uncommitted insert. Queueing first
 * and committing after was a race the worker could win, and losing it left a {@code FAILED} job
 * beside a committed {@code QUEUED} row that froze the network with no job behind it.
 *
 * <h2>The snapshot is taken once, at submission</h2>
 *
 * <p>The {@code NetworkGraph} and the {@code ScenarioPlan} are built here and captured by the job's
 * closure rather than rebuilt on the worker thread. Two reasons: the worker has no transaction, and
 * — more to the point — a run must evaluate the network as it stood when the run was accepted, which
 * is the same guarantee the immutability rule makes at the database level. Both objects are
 * immutable and safe to publish across threads.
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    /** {@code JobHandle.type} for a simulation. Phase 2 will add its own. */
    public static final String JOB_TYPE = "SIMULATION";

    private final NetworkLookup lookup;
    private final NetworkGraphFactory graphs;
    private final DisruptionScenarioRepository scenarios;
    private final ScenarioPlanFactory plans;
    private final SimulationRunRepository runs;
    private final SimulationRunWriter writer;
    private final RunTimeseriesRepository timeseries;
    private final RunNodeTimeseriesRepository nodeTimeseries;
    private final RunLinkTimeseriesRepository linkTimeseries;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final MetricResultRepository metricResults;
    private final MetricResultMapper metricMapper;
    private final MetricCalculatorRegistry registry;
    private final MonteCarloRunner runner;
    private final JobService jobs;
    private final ObjectMapper json;

    SimulationService(NetworkLookup lookup, NetworkGraphFactory graphs,
            DisruptionScenarioRepository scenarios, ScenarioPlanFactory plans,
            SimulationRunRepository runs, SimulationRunWriter writer,
            RunTimeseriesRepository timeseries, RunNodeTimeseriesRepository nodeTimeseries,
            RunLinkTimeseriesRepository linkTimeseries, NodeRepository nodes, LinkRepository links,
            MetricResultRepository metricResults,
            MetricResultMapper metricMapper, MetricCalculatorRegistry registry,
            MonteCarloRunner runner, JobService jobs, ObjectMapper json) {
        this.lookup = lookup;
        this.graphs = graphs;
        this.scenarios = scenarios;
        this.plans = plans;
        this.runs = runs;
        this.writer = writer;
        this.timeseries = timeseries;
        this.nodeTimeseries = nodeTimeseries;
        this.linkTimeseries = linkTimeseries;
        this.nodes = nodes;
        this.links = links;
        this.metricResults = metricResults;
        this.metricMapper = metricMapper;
        this.registry = registry;
        this.runner = runner;
        this.jobs = jobs;
        this.json = json;
    }

    // ------------------------------------------------------------------------ submit

    /**
     * Validates, records and queues one run.
     *
     * @throws EntityNotFoundException     if either id is missing or not the caller's, or if the
     *                                     network is not in the scenario's project
     * @throws com.snrm.scenario.EventHorizonException if an event's window ends after this
     *                                     network's horizon
     * @throws UnresolvedEventException    if an event names nothing in this network
     * @throws NoDemandException           if the network has no demand to serve
     * @throws com.snrm.common.JobQueueFullException if the bounded queue is full (429)
     */
    @Transactional
    public SimulationAcceptedDto submit(SimulationRequest request, long ownerId) {
        Network network = lookup.requireNetwork(request.networkId(), ownerId);
        // Null is the baseline run of FR-17 — the network evaluated with nothing going wrong.
        DisruptionScenario scenario = request.scenarioId() == null ? null
                : requireScenarioOfSameProject(request.scenarioId(), ownerId, network);

        TimeBasis basis = TimeBasis.of(network.getPeriodLength(), network.getRoundingPolicy());
        NetworkGraph graph = graphs.snapshot(network.getId());
        checkHasDemand(graph);

        // Discretises every event onto this network's clock, refuses one that overruns its horizon
        // and resolves every target against its contents. A baseline run has no
        // events to discretise and its plan is empty by construction.
        ScenarioPlan plan = scenario == null ? ScenarioPlan.baseline(network.getId())
                : plans.plan(scenario.getId(), network.getId(), basis, network.getHorizonPeriods());
        if (!plan.unresolvedEventIds().isEmpty()) {
            throw new UnresolvedEventException(network.getId(), scenario.getId(),
                    plan.unresolvedEventIds());
        }

        SimulationParams params = SimulationParams.resolve(request.params(),
                // A baseline run has no scenario to read defaults from: 0 falls through to
                // DEFAULT_REPLICATIONS, null to a drawn seed — the same fallbacks a scenario
                // leaves unset.
                scenario == null ? 0 : scenario.getNumReplications(),
                scenario == null ? null : scenario.getSeed(),
                network.getHorizonPeriods(),
                // Drawn here rather than inside SimulationParams so that class stays pure: the seed
                // it records is an argument, which is what makes resolve() testable.
                ThreadLocalRandom.current().nextLong());

        // 2N with the paired undisrupted set; exactly N for a run that disrupts nothing,
        // where the pairing is skipped (FR-17). One rule, owned by the runner.
        int executed = MonteCarloRunner.executedReplications(plan, params);

        // The run row is COMMITTED before the job is queued — createQueued is REQUIRES_NEW and
        // returns only after its own commit. Writing it inside this method's transaction and then
        // queueing was a race the worker could win: it reads the row through a new transaction
        // (markRunning), which cannot see this one's uncommitted insert, and a worker that beat the
        // commit's fsync died on its first line with "No simulation run with id N" — while the row
        // then committed as QUEUED and froze the network with no job behind it. Committing first
        // also keeps the freeze honest: no worker can ever start against a network that is still
        // editable, because the freeze is durable before the task exists.
        long runId = writer.createQueued(network, scenario, toJson(params));

        JobHandle handle;
        try {
            handle = jobs.submit(JOB_TYPE, runId,
                    progress -> execute(runId, graph, plan, params, progress));
        } catch (RuntimeException refusal) {
            // The bounded queue refused the task (429). The run row is already committed and
            // network-locking, and no worker will ever touch it — deleting it is what lets the
            // caller retry at all, since a QUEUED row with no job would freeze the network for ever.
            writer.discardNeverQueued(runId);
            throw refusal;
        }

        log.info("Queued run {} — network {} v{}, scenario {}, {} replications, seed {}",
                runId, network.getId(), network.getVersion(),
                scenario == null ? "none (baseline run)" : scenario.getId(),
                executed, params.seed());

        return new SimulationAcceptedDto(handle.jobId(), runId, SimulationStatus.QUEUED, params,
                executed);
    }

    // ------------------------------------------------------------------------- the job

    /**
     * The job body: run the replications, compute the suite, persist everything.
     *
     * <p>Runs on a {@link JobService} worker with no transaction and no request context. Each
     * database write goes through {@link SimulationRunWriter}, which opens its own — and the failure
     * and cancellation paths are as important as the happy one, because a run left at
     * {@code RUNNING} would freeze its network for ever.
     */
    private Long execute(long runId, NetworkGraph graph, ScenarioPlan plan, SimulationParams params,
            ProgressSink progress) {
        writer.markRunning(runId);
        try {
            SimulationTraces traces = runner.run(graph, plan, params, progress);

            progress.report(0.95, "Computing the metric suite.");
            List<MetricValue> values =
                    registry.compute(MetricKind.SIMULATED, MetricContext.of(graph, traces));

            progress.report(0.98, "Persisting results.");
            writer.persistResults(runId, values, traces);
            return runId;
        } catch (JobCancelledException cancelled) {
            writer.markCancelled(runId);
            throw cancelled;
        } catch (RuntimeException | Error failure) {
            writer.markFailed(runId);
            throw failure;
        }
    }

    // ------------------------------------------------------------------------- reads

    /** {@code GET /simulations/{runId}} — the run, its metric suite and its curves. */
    @Transactional(readOnly = true)
    public SimulationResultsDto results(long runId, long ownerId) {
        SimulationRun run = requireRun(runId, ownerId);
        return new SimulationResultsDto(toDto(run), metrics(run), series(runId));
    }

    /**
     * {@code GET /networks/{id}/runs} — every run of one network, newest first, without results
     * (FR-20).
     *
     * <p>The read the discard confirmation is built from: it has to state <em>how many</em>
     * results are about to be destroyed and whether any of them is a restored archive result
     * ({@code importedAt}) <strong>before</strong> the user confirms, and until this existed
     * there was no way for a client to know either. It is also the honest answer to "what is holding
     * this network frozen" — the question the frozen banner raises and only the run rows can settle.
     *
     * <p>Records only. Metric rows and curves stay behind {@code GET /simulations/{runId}} for the
     * reason the two are split at all: a network with a dozen runs would otherwise cost a dozen
     * horizons of points to answer a question about counts.
     */
    @Transactional(readOnly = true)
    public List<SimulationRunDto> networkRuns(long networkId, long ownerId) {
        // Ownership through the network rather than through each run: one check, and a network id
        // belonging to another researcher is a 404 before any run row is read.
        lookup.requireNetwork(networkId, ownerId);
        return runs.findByNetworkIdOrderByIdDesc(networkId).stream().map(this::toDto).toList();
    }

    /** {@code GET /simulations/{runId}/results} — the metric suite alone. */
    @Transactional(readOnly = true)
    public List<MetricResultDto> metricResults(long runId, long ownerId) {
        return metrics(requireRun(runId, ownerId));
    }

    /** {@code GET /simulations/{runId}/timeseries} — the per-period curves alone. */
    @Transactional(readOnly = true)
    public List<RunTimeseriesDto> timeseries(long runId, long ownerId) {
        requireRun(runId, ownerId);
        return series(runId);
    }

    /**
     * {@code GET /simulations/{runId}/timeseries/elements} — every node's and every link's own
     * series (FR-18).
     *
     * <p>Ownership first, exactly as {@link #results} and {@link #timeseries} do it: the run is
     * resolved and checked against {@code ownerId} before any element repository is touched, so a
     * run id belonging to another researcher is a 404 and never a shape from which the network's
     * size could be inferred.
     *
     * <p>Deliberately <strong>not</strong> folded into {@link SimulationResultsDto}. The wide read
     * opens a run, and this response is {@code horizon × (nodes + links)} numbers —
     * on a 1,000-node network with a 52-period horizon, half a million of them. Making the results
     * view pay that to render a metric table would be the opposite of what the split is for.
     */
    @Transactional(readOnly = true)
    public ElementTimeseriesDto elementTimeseries(long runId, long ownerId) {
        SimulationRun run = requireRun(runId, ownerId);
        if (!nodeTimeseries.existsForRun(runId)) {
            return ElementTimeseriesDto.unavailable();
        }
        Map<Long, String> names = nodeNames(run);
        return new ElementTimeseriesDto(true,
                nodeSeries(nodeTimeseries.findSeries(runId), names),
                linkSeries(linkTimeseries.findSeries(runId), names, linksById(run)));
    }

    /** {@code GET /simulations/{runId}/timeseries/nodes/{nodeId}} — one node. */
    @Transactional(readOnly = true)
    public ElementTimeseriesDto nodeTimeseries(long runId, long nodeId, long ownerId) {
        SimulationRun run = requireRun(runId, ownerId);
        if (!nodeTimeseries.existsForRun(runId)) {
            return ElementTimeseriesDto.unavailable();
        }
        // available stays true even when this node has no rows: the run recorded, and an element
        // that did not appear in it is a different statement from a run that recorded nothing.
        // No link query here — a node drill-down needs no arc labels, and a scrubber may poll it.
        return new ElementTimeseriesDto(true,
                nodeSeries(nodeTimeseries.findForNode(runId, nodeId), nodeNames(run)), List.of());
    }

    /** {@code GET /simulations/{runId}/timeseries/links/{linkId}} — one link. */
    @Transactional(readOnly = true)
    public ElementTimeseriesDto linkTimeseries(long runId, long linkId, long ownerId) {
        SimulationRun run = requireRun(runId, ownerId);
        if (!nodeTimeseries.existsForRun(runId)) {
            return ElementTimeseriesDto.unavailable();
        }
        return new ElementTimeseriesDto(true, List.of(),
                linkSeries(linkTimeseries.findForLink(runId, linkId), nodeNames(run),
                        linksById(run)));
    }

    // ------------------------------------------------------------------ deletion (FR-20)

    /**
     * {@code DELETE /simulations/{runId}} — the run and everything it produced (FR-20).
     *
     * <h2>The freeze is released by removing the row, and by nothing else</h2>
     *
     * <p>Immutability is <em>derived</em> rather than stored: {@link
     * com.snrm.network.NetworkMutationGuard} asks {@code existsByNetworkIdAndStatusIn} on every
     * mutation, so a network whose last {@linkplain SimulationStatus#isNetworkLocking() locking} run
     * is deleted is editable again the moment this transaction commits. There is no flag to clear and
     * nothing to notify — which is exactly why FR-20's exit could be deletion rather than an
     * exemption, and why a run that is <em>kept</em> is as network-freezing as it ever was.
     *
     * <h2>Everything the run owns goes with it, by declared cascade</h2>
     *
     * <p>Four foreign keys carry {@code ON DELETE CASCADE} to {@code simulation_run(id)}, and between
     * them they are the whole of "everything the run produced":
     *
     * <ul>
     *   <li>{@code fk_mr_run} — {@code metric_result.run_id} ({@code V2__domain.sql})</li>
     *   <li>{@code fk_timeseries_run} — {@code run_timeseries} ({@code V2__domain.sql})</li>
     *   <li>{@code fk_node_ts_run} — {@code run_node_timeseries} ({@code V9__element_timeseries.sql})</li>
     *   <li>{@code fk_link_ts_run} — {@code run_link_timeseries} ({@code V9})</li>
     * </ul>
     *
     * <p>So no child rows are deleted here explicitly, and that is a decision rather than an
     * omission. Deleting them in application code as well would mean two definitions of what a run
     * owns, and the one that silently stops being maintained is always the code — a fifth results
     * table added later would cascade correctly and be forgotten by a hand-written sweep. The
     * element series is also the volume case (horizon × elements, ~52,000 rows on FR-04 networks) and
     * InnoDB deletes those in the same statement rather than as 52,000 managed entities. {@code
     * RunDeletionTest} pins the cascade against a real MySQL, because a cascade that is relied on and
     * not tested is a cascade that gets dropped in a later migration.
     *
     * <p><strong>Topological metric rows are untouched</strong>: they carry {@code run_id = NULL}
     * and belong to the network, not to any run. A network whose runs are all discarded
     * keeps its structural suite, which is correct — nothing about the structure changed.
     *
     * <p><strong>A restored run is deletable like any other</strong>. {@code imported_at}
     * marks results this instance did not compute, and destroying them destroys the only copy this
     * installation holds — but the archive it came from is a file the researcher still has, and
     * refusing here would make a restored project permanently uneditable. The warning belongs in the
     * confirmation, not in a refusal.
     *
     * @throws EntityNotFoundException if the run is missing or is another user's — the same answer
     *                                 every other run read gives
     * @throws RunActiveException      409 {@code RUN_ACTIVE} while the run is QUEUED or RUNNING
     */
    @Transactional
    public void delete(long runId, long ownerId) {
        SimulationRun run = requireRun(runId, ownerId);
        if (run.getStatus().isActive()) {
            throw new RunActiveException(runId, run.getStatus());
        }
        long networkId = run.getNetwork().getId();
        runs.delete(run);
        log.info("Deleted run {} of network {} (status {}{})", runId, networkId, run.getStatus(),
                run.isImported() ? ", restored from an archive" : "");
    }

    /**
     * {@code DELETE /networks/{id}/runs} — every run of one network, in one request (FR-20).
     *
     * <p>The editor's "discard this network's runs and edit in place". One request rather
     * than N, because the researcher's question is about the <em>network</em> — "stop this being
     * frozen" — and a client looping over runs would release the freeze somewhere in the middle of
     * its own loop, with no way to report what happened if the third call failed.
     *
     * <p><strong>Refused whole while any run is active</strong>, never partially applied. See
     * {@link RunActiveException} for why a half-discard is worse than either whole answer. The check
     * runs over the same list that is then deleted, inside one transaction, so the answer cannot
     * change between deciding and acting.
     *
     * <p>A network with no runs at all is a success, not a 404: the caller asked for a network with
     * no runs and that is what they have. Making it an error would mean the editor had to know the
     * count before it could ask, and two clicks on the same button would report a failure on the
     * second.
     *
     * @return how many runs were deleted, for the log and the caller's own accounting
     * @throws EntityNotFoundException if the network is missing or is another user's
     * @throws RunActiveException      409 {@code RUN_ACTIVE} if any run is QUEUED or RUNNING
     */
    @Transactional
    public int deleteNetworkRuns(long networkId, long ownerId) {
        lookup.requireNetwork(networkId, ownerId);
        List<SimulationRun> all = runs.findByNetworkIdOrderByIdDesc(networkId);
        List<SimulationRun> active = all.stream()
                .filter(run -> run.getStatus().isActive())
                .toList();
        if (!active.isEmpty()) {
            throw new RunActiveException(networkId, active);
        }
        runs.deleteAll(all);
        log.info("Discarded {} run(s) of network {}; the network is editable again unless another "
                + "run is created (FR-20)", all.size(), networkId);
        return all.size();
    }

    private List<MetricResultDto> metrics(SimulationRun run) {
        List<MetricResult> rows = metricResults.findByRunId(run.getId());
        List<MetricResultDto> dtos = new ArrayList<>(rows.size());
        for (MetricResult row : rows) {
            // Every simulated metric in the suite is network-scoped, so there is no element
            // name to attach — unlike NODE_CRITICALITY on the topological side.
            dtos.add(metricMapper.toDto(row, null));
        }
        return dtos;
    }

    private List<RunTimeseriesDto> series(long runId) {
        return timeseries.findSeries(runId).stream().map(RunTimeseriesDto::of).toList();
    }

    // ------------------------------------------------------------- per-element series

    /**
     * The labels a per-element chart needs, which the result rows deliberately do not carry.
     *
     * <p>{@code run_node_timeseries} stores a bare {@code node_id} — a results row is a statement
     * about a run and must not be a reason a node cannot be deleted (see the entity). So the names
     * are read back from the network, which is safe to do because a run <em>freezes</em> its network:
     * the labels cannot have drifted from what the run evaluated.
     *
     * <p>Two queries, not {@code n + 1}. A link's endpoints are {@code LAZY} associations, so
     * {@code getSourceNode().getId()} reads the proxy's identifier without loading the node, and the
     * name comes out of the map built from the single node query.
     */
    private Map<Long, String> nodeNames(SimulationRun run) {
        Map<Long, String> names = new HashMap<>();
        for (Node node : nodes.findByNetworkId(run.getNetwork().getId())) {
            names.put(node.getId(), node.getName());
        }
        return names;
    }

    /** Read only where an arc has to be labelled — a node drill-down needs no link query. */
    private Map<Long, Link> linksById(SimulationRun run) {
        Map<Long, Link> byId = new HashMap<>();
        for (Link link : links.findByNetworkId(run.getNetwork().getId())) {
            byId.put(link.getId(), link);
        }
        return byId;
    }

    /**
     * Groups the rows of one or more nodes into the parallel-array form the API returns.
     *
     * <p>The rows arrive element-major then period-major — the primary key's own order — so a
     * grouping pass followed by a period-ordered fill is a single walk. {@code inbound_lead}'s
     * {@code NULL}s survive as {@code null} entries; nothing here may turn one into a 0.
     */
    private static List<NodeTimeseriesDto> nodeSeries(List<RunNodeTimeseries> rows,
            Map<Long, String> nodeNames) {
        Map<Long, List<RunNodeTimeseries>> byNode = new LinkedHashMap<>();
        for (RunNodeTimeseries row : rows) {
            byNode.computeIfAbsent(row.getNodeId(), id -> new ArrayList<>()).add(row);
        }
        List<NodeTimeseriesDto> series = new ArrayList<>(byNode.size());
        for (Map.Entry<Long, List<RunNodeTimeseries>> entry : byNode.entrySet()) {
            List<RunNodeTimeseries> periods = entry.getValue();
            int horizon = periods.size();
            double[] onHand = new double[horizon];
            double[] inTransit = new double[horizon];
            double[] arrivals = new double[horizon];
            double[] served = new double[horizon];
            double[] unserved = new double[horizon];
            double[] throughput = new double[horizon];
            double[] availability = new double[horizon];
            List<Double> inboundLead = new ArrayList<>(horizon);
            double[] baselineOnHand = new double[horizon];
            double[] baselineServed = new double[horizon];
            for (int t = 0; t < horizon; t++) {
                RunNodeTimeseries row = periods.get(t);
                onHand[t] = row.getOnHand();
                inTransit[t] = row.getInTransit();
                arrivals[t] = row.getArrivals();
                served[t] = row.getServed();
                unserved[t] = row.getUnserved();
                throughput[t] = row.getThroughput();
                availability[t] = row.getAvailability();
                inboundLead.add(row.getInboundLead());
                baselineOnHand[t] = row.getBaselineOnHand();
                baselineServed[t] = row.getBaselineServed();
            }
            series.add(new NodeTimeseriesDto(entry.getKey(), nodeNames.get(entry.getKey()),
                    onHand, inTransit, arrivals, served, unserved, throughput, availability,
                    inboundLead, baselineOnHand, baselineServed));
        }
        return series;
    }

    /** The same for links, with the two endpoint names a bare arc id cannot be labelled without. */
    private static List<LinkTimeseriesDto> linkSeries(List<RunLinkTimeseries> rows,
            Map<Long, String> nodeNames, Map<Long, Link> linksById) {
        Map<Long, List<RunLinkTimeseries>> byLink = new LinkedHashMap<>();
        for (RunLinkTimeseries row : rows) {
            byLink.computeIfAbsent(row.getLinkId(), id -> new ArrayList<>()).add(row);
        }
        List<LinkTimeseriesDto> series = new ArrayList<>(byLink.size());
        for (Map.Entry<Long, List<RunLinkTimeseries>> entry : byLink.entrySet()) {
            List<RunLinkTimeseries> periods = entry.getValue();
            int horizon = periods.size();
            double[] flow = new double[horizon];
            List<Double> utilisation = new ArrayList<>(horizon);
            double[] availability = new double[horizon];
            double[] baselineFlow = new double[horizon];
            for (int t = 0; t < horizon; t++) {
                RunLinkTimeseries row = periods.get(t);
                flow[t] = row.getFlow();
                utilisation.add(row.getUtilisation());
                availability[t] = row.getAvailability();
                baselineFlow[t] = row.getBaselineFlow();
            }
            Link link = linksById.get(entry.getKey());
            series.add(new LinkTimeseriesDto(entry.getKey(),
                    link == null ? null : nodeNames.get(link.getSourceNode().getId()),
                    link == null ? null : nodeNames.get(link.getTargetNode().getId()),
                    flow, utilisation, availability, baselineFlow));
        }
        return series;
    }

    private SimulationRunDto toDto(SimulationRun run) {
        Network network = run.getNetwork();
        // The clock travels with the run because nothing in the results can be read without it —
        // the curve's x-axis unit and TTR's meaning both come from it. Read from
        // the network rather than stored on the run: a locking run freezes its network,
        // so the two cannot disagree, and a copied column could.
        DurationDto periodLength = DurationDto.of(network.getPeriodLength().getValue(),
                network.getPeriodLength().getUnit());
        // Null scenario fields are the baseline run of FR-17 — a client labels it, never invents a
        // scenario name for it.
        DisruptionScenario scenario = run.getScenario();
        return new SimulationRunDto(run.getId(), network.getId(),
                network.getName(),
                scenario == null ? null : scenario.getId(),
                scenario == null ? null : scenario.getName(),
                run.getStatus(), run.getStartedAt(), run.getFinishedAt(),
                periodLength, network.getHorizonPeriods(),
                fromJson(run.getParamsJson()), run.getImportedAt(), run.getSourceRunId(),
                List.of());
    }

    // ------------------------------------------------------------------------ checks

    /** {@link NoDemandException}: a run with nothing to serve reports perfect service. */
    private static void checkHasDemand(NetworkGraph graph) {
        SimulationNetwork network = SimulationNetwork.of(graph);
        if (network.totalDemandPerPeriod() <= 0) {
            throw new NoDemandException(graph.networkId(), network.customers().length);
        }
    }

    private DisruptionScenario requireScenarioOfSameProject(long scenarioId, long ownerId,
            Network network) {
        DisruptionScenario scenario = scenarios.findWithEventsById(scenarioId)
                .filter(found -> found.getProject().getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No scenario with id " + scenarioId));
        if (!scenario.getProject().getId().equals(network.getProject().getId())) {
            // Missing and not-in-this-project are the same answer, following NetworkLookup: telling
            // the caller a scenario exists elsewhere is information they have not earned.
            throw new EntityNotFoundException(
                    "No scenario with id %d in this network's project".formatted(scenarioId));
        }
        return scenario;
    }

    private SimulationRun requireRun(long runId, long ownerId) {
        return runs.findById(runId)
                .filter(run -> run.getNetwork().getProject().getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No simulation run with id " + runId));
    }

    // --------------------------------------------------------------------- params_json

    /**
     * The resolved parameter set as the {@code JSON} column holds it.
     *
     * <p>Through the application's own {@link ObjectMapper}, so the stored document follows the same
     * conventions the API does and a researcher reading {@code params_json} directly sees what the
     * API showed them.
     */
    private String toJson(SimulationParams params) {
        try {
            return json.writeValueAsString(params);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialise the run parameters", failure);
        }
    }

    /**
     * Reads a stored parameter set back.
     *
     * <p>A run written by an earlier engine version may carry fields this one does not know, or lack
     * ones it expects. Rather than failing the read — which would make an old run unreadable and
     * defeat the point of storing the parameters at all — an unparseable document yields null and
     * the rest of the run is returned. {@code engineVersion} is what a reader checks.
     */
    private SimulationParams fromJson(String paramsJson) {
        try {
            return json.readValue(paramsJson, SimulationParams.class);
        } catch (JsonProcessingException failure) {
            log.warn("Could not read params_json; returning the run without its parameters", failure);
            return null;
        }
    }
}
