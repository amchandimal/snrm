package com.snrm.metrics;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The answer to {@code GET /networks/{id}/metrics/topological}.
 *
 * <p>The suite is computed, persisted and returned in one call. Persisting it is not a side effect
 * the caller can decline: the comparison view reads stored metric rows for networks it is
 * not currently displaying, so a suite that existed only in a response would leave a variant absent
 * from its own comparison until someone happened to open it.
 *
 * <p>{@link #computedInMs} is here because FR-04 states a budget — two seconds at a thousand nodes —
 * and a budget nobody can observe is not a requirement. It is wall-clock time for the snapshot build
 * plus every calculator, excluding the write.
 *
 * @param networkId    the network the suite describes
 * @param computedInMs wall-clock cost of the computation (FR-04)
 * @param metrics      every value, network-scoped ones first, in suite order
 */
@Schema(name = "TopologicalMetrics",
        description = "The structural metric suite for one network, computed on demand "
                + "and persisted against the network with no run.")
public record TopologicalMetricsResponse(

        @Schema(description = "The network the suite describes.", example = "1")
        Long networkId,

        @Schema(description = "Wall-clock cost of the computation in milliseconds. FR-04 budgets "
                + "2,000 ms at 1,000 nodes.", example = "42")
        long computedInMs,

        @Schema(description = "Every value in the suite, in calculator order.")
        List<MetricResultDto> metrics) {
}
