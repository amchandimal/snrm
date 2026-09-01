package com.snrm.metrics;

/**
 * How a metric is computed.
 *
 * <p>Distinct from {@link MetricScope}, which says what a value is <em>about</em>. Kind decides
 * <em>when</em> a calculator runs and <em>what</em> its {@code MetricContext} carries.
 *
 * <p>Not persisted: {@code metric_result} stores the code and the scope, and the kind is a property
 * of the calculator rather than of the value. It is inferable from the row anyway — a topological
 * result has {@code run_id = NULL}.
 */
public enum MetricKind {

    /**
     * Computed synchronously from the {@code NetworkGraph} snapshot alone, on save or on demand.
     * Exact, so no confidence interval; persisted against the network with
     * {@code run_id = NULL}, because the value belongs to the structure rather than to any run.
     */
    TOPOLOGICAL,

    /**
     * Computed once per simulation job from the per-replication traces, aggregated into a mean and
     * a 95% confidence interval. Persisted against the run.
     */
    SIMULATED
}
