package com.snrm.metrics;

import com.snrm.common.TimeUnit;

/**
 * One value a {@link MetricCalculator} produced, before it is persisted.
 *
 * <p>The engine-side counterpart of {@link MetricResult}: same fields, no JPA, no identity, and
 * nothing that would let a calculator reach the database. A calculator returns a list of
 * these and the registry writes them.
 *
 * <p><strong>{@code displayUnit} is not the calculator's to set.</strong> A time-valued metric is
 * computed in periods and the unit those periods should be read in is a property of the
 * network's clock, not of the metric — so {@link MetricCalculatorRegistry} fills it in from the
 * snapshot's {@code TimeBasis} for calculators that declare {@link MetricCalculator#timeValued()}.
 * That is what keeps {@code TimeUnit} out of every calculator.
 *
 * @param code        the metric code, e.g. {@code DENSITY}
 * @param scope       what the value is about ({@code METRIC_RESULT.scope})
 * @param scopeId     the node or link id for a scoped value; null at {@link MetricScope#NETWORK}
 * @param scopeName   that element's name, for labelling; null at network scope. Never persisted —
 *                    it is carried so the response can be read without a second lookup
 * @param value       the number
 * @param ciLow       lower bound of the 95% interval across replications, or null
 * @param ciHigh      upper bound, or null
 * @param displayUnit unit {@code value} should be read in, or null for a dimensionless metric
 */
public record MetricValue(
        String code,
        MetricScope scope,
        Long scopeId,
        String scopeName,
        double value,
        Double ciLow,
        Double ciHigh,
        TimeUnit displayUnit) {

    /** A whole-network value: a density, an average, a count. */
    public static MetricValue network(String code, double value) {
        return new MetricValue(code, MetricScope.NETWORK, null, null, value, null, null, null);
    }

    /** A per-node value — one row per node, as {@code NODE_CRITICALITY} produces. */
    public static MetricValue node(String code, long nodeId, String nodeName, double value) {
        return new MetricValue(code, MetricScope.NODE, nodeId, nodeName, value, null, null, null);
    }

    /** This value restated in a unit — the registry's one write, for time-valued metrics. */
    public MetricValue withDisplayUnit(TimeUnit unit) {
        return new MetricValue(code, scope, scopeId, scopeName, value, ciLow, ciHigh, unit);
    }
}
