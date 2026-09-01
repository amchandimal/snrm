package com.snrm.simulation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link RunLinkTimeseries}: the natural {@code (run_id, link_id, period)} triple.
 *
 * <p>The link-side twin of {@link RunNodeTimeseriesId}, and element-major for the same reason: the
 * access path is one arc's whole horizon, which is what a flow ribbon animates over.
 */
@Embeddable
public class RunLinkTimeseriesId implements Serializable {

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "link_id")
    private Long linkId;

    @Column(name = "period")
    private Integer period;

    protected RunLinkTimeseriesId() {
        // for JPA
    }

    public RunLinkTimeseriesId(Long runId, Long linkId, Integer period) {
        this.runId = runId;
        this.linkId = linkId;
        this.period = period;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getLinkId() {
        return linkId;
    }

    public Integer getPeriod() {
        return period;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RunLinkTimeseriesId that)) {
            return false;
        }
        return Objects.equals(runId, that.runId)
                && Objects.equals(linkId, that.linkId)
                && Objects.equals(period, that.period);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, linkId, period);
    }

    @Override
    public String toString() {
        return "RunLinkTimeseriesId[run=" + runId + ", link=" + linkId + ", period=" + period + "]";
    }
}
