package com.snrm.metrics;

import com.snrm.common.TimeUnit;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One metric value as the API returns it ({@code METRIC_RESULT}, DTO boundary).
 *
 * <p>Narrow on purpose, exactly as the table is: {@link #metricCode} is an opaque string, so a new
 * metric adds rows and never a field. A client that does not recognise a code should display it
 * generically rather than treat it as an error — that is what makes "new metrics are added by
 * implementing an interface" true on the front end as well as the back.
 *
 * <p>{@link #scopeName} is the one field with no column behind it. It is the name of the node or
 * link {@link #scopeId} points at, carried so that a per-node criticality table can be rendered from
 * this response alone instead of being joined against a separately fetched node list — and
 * so that a value stays readable in an export after the element it describes has been renamed.
 *
 * @param id          surrogate key of the persisted row
 * @param networkId   the network the value belongs to
 * @param runId       the simulation run, or null for a topological metric
 * @param metricCode  the code
 * @param scope       whether the value is about the network, a node or a link
 * @param scopeId     that node's or link's id; null at network scope
 * @param scopeName   that element's name; null at network scope
 * @param value       the number
 * @param ciLow       lower bound of the 95% interval across replications, or null
 * @param ciHigh      upper bound, or null
 * @param displayUnit unit {@link #value} is expressed over, or null if dimensionless
 */
@Schema(name = "MetricResult",
        description = "One computed metric value from the suite.")
public record MetricResultDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "The network this value belongs to.", example = "1")
        Long networkId,

        @Schema(description = "The simulation run that produced it, or null for a topological "
                + "metric — those belong to the network rather than to any run.",
                example = "null")
        Long runId,

        @Schema(description = "Metric code from the suite. Opaque: a client should render "
                + "an unfamiliar code rather than reject it.", example = "NODE_CRITICALITY")
        String metricCode,

        @Schema(description = "What the value is about.", example = "NODE")
        MetricScope scope,

        @Schema(description = "Node or link id for a scoped value; null at NETWORK scope.",
                example = "3")
        Long scopeId,

        @Schema(description = "Name of the node or link `scopeId` points at; null at NETWORK scope. "
                + "Derived, not stored.", example = "DC-1")
        String scopeName,

        @Schema(description = "The value.", example = "0.6153846153846154")
        double value,

        @Schema(description = "Lower bound of the 95% CI across Monte Carlo replications. Null for "
                + "topological metrics, which are exact.", example = "null")
        Double ciLow,

        @Schema(description = "Upper bound of the 95% CI.", example = "null")
        Double ciHigh,

        @Schema(description = "The unit `value` is expressed over, for time-valued metrics such as "
                + "TTR; null where the metric is dimensionless.", example = "null")
        TimeUnit displayUnit) {
}
