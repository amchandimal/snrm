package com.snrm.simulation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for {@link RunTimeseries}, a child of the {@link SimulationRun} aggregate.
 *
 * <p>Queries reach through the composite key explicitly rather than relying on a derived name, so
 * the access path stays legible: one run's periods in order is a clustered scan of the primary key.
 */
public interface RunTimeseriesRepository extends JpaRepository<RunTimeseries, RunTimeseriesId> {

    /** The performance curve of one run, in period order. */
    @Query("select t from RunTimeseries t where t.id.runId = :runId order by t.id.period asc")
    List<RunTimeseries> findSeries(@Param("runId") Long runId);
}
