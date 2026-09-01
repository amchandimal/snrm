package com.snrm.metrics;

/**
 * What a {@link MetricResult} value describes ({@code METRIC_RESULT.scope}).
 *
 * <p>Distinct from {@code MetricKind}, which says <em>how</em> a metric is computed
 * (topological vs. simulated). Scope says <em>what it is about</em>: {@code DENSITY} is
 * {@link #NETWORK}-scoped, {@code NODE_CRITICALITY} produces one {@link #NODE}-scoped row per node.
 *
 * <p>Persisted as a MySQL {@code ENUM} — keep the literals in step with {@code V2__domain.sql}.
 */
public enum MetricScope {

    /** Whole-network value; {@code scope_id} is null. */
    NETWORK,

    /** Per-node value; {@code scope_id} is a {@code node.id}. */
    NODE,

    /** Per-link value; {@code scope_id} is a {@code link.id}. */
    LINK
}
