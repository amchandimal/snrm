package com.snrm.simulation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for {@link RunLinkTimeseries}, a child of the {@link SimulationRun} aggregate.
 *
 * <p>The arc-side twin of {@link RunNodeTimeseriesRepository}; there is no {@code existsForRun} here
 * because that question is about the run and is answered once, on the node side — a network with no
 * links would otherwise report a recorded run as unavailable.
 */
public interface RunLinkTimeseriesRepository
        extends JpaRepository<RunLinkTimeseries, RunLinkTimeseriesId> {

    /** Every link's whole horizon, element-major then period-major. */
    @Query("select t from RunLinkTimeseries t where t.id.runId = :runId "
            + "order by t.id.linkId asc, t.id.period asc")
    List<RunLinkTimeseries> findSeries(@Param("runId") Long runId);

    /** One link's whole horizon — the drill-down of {@code /timeseries/links/{linkId}}. */
    @Query("select t from RunLinkTimeseries t where t.id.runId = :runId and t.id.linkId = :linkId "
            + "order by t.id.period asc")
    List<RunLinkTimeseries> findForLink(@Param("runId") Long runId, @Param("linkId") Long linkId);
}
