package com.snrm.simulation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for {@link RunNodeTimeseries}, a child of the {@link SimulationRun} aggregate.
 *
 * <p>Queries reach through the composite key explicitly rather than relying on a derived name, the
 * way {@link RunTimeseriesRepository} does, so the access path stays legible: every one of them is a
 * clustered scan of a prefix of {@code (run_id, node_id, period)}.
 */
public interface RunNodeTimeseriesRepository
        extends JpaRepository<RunNodeTimeseries, RunNodeTimeseriesId> {

    /** Every node's whole horizon, element-major then period-major. */
    @Query("select t from RunNodeTimeseries t where t.id.runId = :runId "
            + "order by t.id.nodeId asc, t.id.period asc")
    List<RunNodeTimeseries> findSeries(@Param("runId") Long runId);

    /** One node's whole horizon — the drill-down of {@code /timeseries/nodes/{nodeId}}. */
    @Query("select t from RunNodeTimeseries t where t.id.runId = :runId and t.id.nodeId = :nodeId "
            + "order by t.id.period asc")
    List<RunNodeTimeseries> findForNode(@Param("runId") Long runId, @Param("nodeId") Long nodeId);

    /**
     * Whether this run recorded a per-element series at all.
     *
     * <p>What the API's {@code available} flag answers, and it must be asked separately from
     * fetching one element's rows: a run that predates {@code V9__element_timeseries.sql} or ran
     * with {@code recordElementTimeseries: false} has no rows for <em>any</em> element, which is a
     * different statement from an element that happens to have none. The node side is the one asked,
     * because a network always has nodes and may legitimately have no links.
     */
    @Query("select count(t) > 0 from RunNodeTimeseries t where t.id.runId = :runId")
    boolean existsForRun(@Param("runId") Long runId);
}
