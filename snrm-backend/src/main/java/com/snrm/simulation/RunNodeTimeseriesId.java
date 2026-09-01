package com.snrm.simulation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link RunNodeTimeseries}: the natural {@code (run_id, node_id, period)} triple.
 *
 * <p>The same reasoning {@link RunTimeseriesId} states, one level down. There is no surrogate
 * identifier because the triple <em>is</em> the identity of the row, and it is also the access path:
 * one node's whole horizon in order is a clustered scan of a primary-key prefix, which is exactly
 * what {@code GET /simulations/{runId}/timeseries/nodes/{nodeId}} asks for.
 *
 * <p>Element before period, deliberately. The drill-down is by element — a playback view scrubs one
 * node's inventory across the horizon — so element-major clusters the rows a reader wants together.
 * Period-major would suit "everything that happened in period t", which no endpoint asks for.
 */
@Embeddable
public class RunNodeTimeseriesId implements Serializable {

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "period")
    private Integer period;

    protected RunNodeTimeseriesId() {
        // for JPA
    }

    public RunNodeTimeseriesId(Long runId, Long nodeId, Integer period) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.period = period;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public Integer getPeriod() {
        return period;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RunNodeTimeseriesId that)) {
            return false;
        }
        return Objects.equals(runId, that.runId)
                && Objects.equals(nodeId, that.nodeId)
                && Objects.equals(period, that.period);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, nodeId, period);
    }

    @Override
    public String toString() {
        return "RunNodeTimeseriesId[run=" + runId + ", node=" + nodeId + ", period=" + period + "]";
    }
}
