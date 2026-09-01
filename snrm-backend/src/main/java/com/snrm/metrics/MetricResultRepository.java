package com.snrm.metrics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link MetricResult}.
 *
 * <p>Values live in one narrow table for both metric kinds; the split is on {@code run_id} being
 * null (topological, belongs to the network) or set (simulated, belongs to the run).
 */
public interface MetricResultRepository extends JpaRepository<MetricResult, Long> {

    /** Simulated metrics of one run — the results dashboard payload. */
    List<MetricResult> findByRunId(Long runId);

    /** One metric of one run, e.g. every {@code NODE_CRITICALITY} row. */
    List<MetricResult> findByRunIdAndMetricCode(Long runId, String metricCode);

    /** Topological metrics of a network — those with no run. */
    List<MetricResult> findByNetworkIdAndRunIsNull(Long networkId);

    /**
     * Cross-variant metric matrix for the comparison view. Uses the leading
     * {@code network_id} column of {@code ix_mr}.
     */
    @Query("""
            select m from MetricResult m
            where m.network.id in :networkIds and m.metricCode in :metricCodes
            order by m.network.id, m.metricCode""")
    List<MetricResult> findForComparison(@Param("networkIds") Collection<Long> networkIds,
            @Param("metricCodes") Collection<String> metricCodes);

    /**
     * Every network-scoped value held for a set of networks — the one query behind the comparison
     * matrix.
     *
     * <p>Both halves of the suite in one read: rows with {@code run_id IS NULL} are the network's
     * topological metrics and rows with a run are that run's simulated ones.
     * {@code ComparisonService} partitions them, which is why this does not filter on a run — a
     * network may have several, and which one a column reads from is a decision that needs the run
     * table, not this one.
     *
     * <p><strong>{@code NETWORK} scope only, and that is not an optimisation.</strong>
     * {@code NODE_CRITICALITY} produces one row per node; a matrix whose rows are metrics
     * has nowhere to put fifty of them, and they are already rendered — per node, sorted worst
     * first — by the results dashboard's criticality table. Fetching them here would load
     * the largest part of the table to discard it.
     */
    @Query("""
            select m from MetricResult m
            where m.network.id in :networkIds and m.scope = com.snrm.metrics.MetricScope.NETWORK
            order by m.network.id, m.metricCode""")
    List<MetricResult> findNetworkScopedByNetworkIds(
            @Param("networkIds") Collection<Long> networkIds);

    /**
     * Clears a network's topological metrics before they are recomputed on save/edit.
     * Run-bound rows are never touched: a completed run's results are immutable.
     */
    @Modifying
    @Query("delete from MetricResult m where m.network.id = :networkId and m.run is null")
    int deleteTopologicalByNetworkId(@Param("networkId") Long networkId);
}
