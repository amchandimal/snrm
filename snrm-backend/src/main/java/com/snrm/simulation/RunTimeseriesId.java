package com.snrm.simulation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link RunTimeseries}: the natural {@code (run_id, period)} pair.
 *
 * <p>The ER model gives {@code RUN_TIMESERIES} no surrogate identifier, and the pair is
 * also the access path — a performance curve is a clustered scan of one run's periods in order —
 * so the natural key is kept as the primary key.
 */
@Embeddable
public class RunTimeseriesId implements Serializable {

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "period")
    private Integer period;

    protected RunTimeseriesId() {
        // for JPA
    }

    public RunTimeseriesId(Long runId, Integer period) {
        this.runId = runId;
        this.period = period;
    }

    public Long getRunId() {
        return runId;
    }

    public Integer getPeriod() {
        return period;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RunTimeseriesId that)) {
            return false;
        }
        return Objects.equals(runId, that.runId) && Objects.equals(period, that.period);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, period);
    }

    @Override
    public String toString() {
        return "RunTimeseriesId[run=" + runId + ", period=" + period + "]";
    }
}
