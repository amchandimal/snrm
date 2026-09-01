package com.snrm.metrics;

import com.snrm.network.Network;
import com.snrm.network.NetworkGraph;
import com.snrm.network.NetworkGraphFactory;
import com.snrm.network.NetworkLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes, persists and returns the topological metric suite
 * ({@code GET /networks/{id}/metrics/topological}).
 *
 * <p>This class is the metric module's <strong>persistence edge</strong> and the only place in it
 * that touches a repository. The calculators either side of it see a {@code NetworkGraph} snapshot
 * and return value objects; nothing they do depends on a row existing. That is what
 * lets the Phase 2 configuration engine call {@link MetricCalculatorRegistry} directly to score a
 * candidate configuration it will never persist.
 *
 * <h2>What "persist" means here</h2>
 *
 * <p>Topological metrics are a property of the network, not of a run, so they are written with
 * {@code run_id = NULL}. A recompute <strong>replaces</strong> the network's run-less rows
 * rather than adding to them: the suite describes the structure as it stands, two generations of
 * {@code DENSITY} for one network would be a contradiction rather than a history, and
 * {@code metric_result} carries no unique key that could resolve which was current. Run-bound rows
 * are never touched — a completed run is immutable.
 *
 * <p><strong>Not guarded by {@code NetworkMutationGuard}.</strong> Every other write path into a
 * network passes through it, and this one deliberately does not: a metric is a result computed
 * <em>about</em> the network rather than a change to it, and refusing to compute the suite for a
 * frozen network would refuse it for exactly the networks that have been simulated and are therefore
 * most worth looking at. The values cannot drift either — a frozen network cannot change, so a
 * recompute writes what was already there.
 */
@Service
public class TopologicalMetricsService {

    private final NetworkLookup lookup;
    private final NetworkGraphFactory graphs;
    private final MetricCalculatorRegistry registry;
    private final MetricResultRepository results;
    private final MetricResultMapper mapper;

    TopologicalMetricsService(NetworkLookup lookup, NetworkGraphFactory graphs,
            MetricCalculatorRegistry registry, MetricResultRepository results,
            MetricResultMapper mapper) {
        this.lookup = lookup;
        this.graphs = graphs;
        this.registry = registry;
        this.results = results;
        this.mapper = mapper;
    }

    /**
     * The suite for one network: computed from a fresh snapshot, written, and returned.
     *
     * @throws jakarta.persistence.EntityNotFoundException if the network is missing or belongs to
     *                                                     another user
     */
    @Transactional
    public TopologicalMetricsResponse computeAndPersist(long networkId, long ownerId) {
        Network network = lookup.requireNetwork(networkId, ownerId);

        long startedAt = System.nanoTime();
        NetworkGraph graph = graphs.snapshot(networkId);
        List<MetricValue> values = registry.compute(MetricKind.TOPOLOGICAL, MetricContext.of(graph));
        long computedInMs = (System.nanoTime() - startedAt) / 1_000_000;

        results.deleteTopologicalByNetworkId(networkId);

        List<MetricResult> rows = new ArrayList<>(values.size());
        for (MetricValue value : values) {
            // run = null: a topological metric belongs to the network.
            MetricResult row = new MetricResult(network, null, value.code(), value.scope(),
                    value.scopeId(), value.value());
            row.setCiLow(value.ciLow());
            row.setCiHigh(value.ciHigh());
            row.setDisplayUnit(value.displayUnit());
            rows.add(row);
        }
        List<MetricResult> saved = results.saveAll(rows);

        List<MetricResultDto> dtos = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            // saveAll preserves order, so the value beside each row is the one it came from — which
            // is where the element name lives, since the table has no column for it.
            dtos.add(mapper.toDto(saved.get(i), values.get(i).scopeName()));
        }
        return new TopologicalMetricsResponse(networkId, computedInMs, dtos);
    }
}
