/**
 * MetricCalculator SPI, topological and simulated calculators, and the metric registry,
 * plus the {@link com.snrm.metrics.MetricResult} table those values are persisted to.
 *
 * <p><strong>Engine isolation.</strong> The calculators read only the immutable {@code NetworkGraph}
 * snapshot and the replication traces, and return value objects — never JPA entities, repositories
 * or web classes. The entity and repository here are the module's persistence edge,
 * used by the registry when it writes results; a calculator that reaches for either is a design bug.
 *
 * <p>Each calculator's Javadoc cites the SLR-source definition it implements; adding a
 * metric is one new class and no schema change, since {@code metric_code} is an opaque string.
 *
 * <h2>The shape of the module</h2>
 *
 * <ul>
 *   <li>{@link com.snrm.metrics.MetricCalculator} is the SPI, with
 *       {@link com.snrm.metrics.MetricContext} for what a calculator may read and
 *       {@link com.snrm.metrics.MetricValue} for what it returns.</li>
 *   <li>{@link com.snrm.metrics.MetricCalculatorRegistry} discovers every implementation through
 *       Spring and runs the ones of a given {@link com.snrm.metrics.MetricKind}. It stays on the
 *       engine side and writes nothing, so Phase 2 can score a candidate configuration
 *       through it without a database.</li>
 *   <li>{@link com.snrm.metrics.topological} holds the seven structural calculators and
 *       the graph machinery they share.</li>
 *   <li>{@link com.snrm.metrics.TopologicalMetricsService} is the module's persistence edge —
 *       compute, replace the network's run-less rows, return — behind
 *       {@link com.snrm.metrics.MetricController} at
 *       {@code GET /networks/{id}/metrics/topological}.</li>
 * </ul>
 *
 * <p>{@code docs/metric-verification.md} works a six-node network through every topological metric
 * by hand and states the values this module must return for it.
 */
package com.snrm.metrics;
