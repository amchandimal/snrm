package com.snrm.metrics;

import java.util.List;

/**
 * One metric of the suite (Strategy + registry).
 *
 * <blockquote><pre>
 * public interface MetricCalculator {
 *     String code();
 *     MetricKind kind();              // TOPOLOGICAL | SIMULATED
 *     List&lt;MetricValue&gt; compute(MetricContext ctx);
 * }
 * </pre></blockquote>
 *
 * <p>Implementations are Spring beans, discovered by {@link MetricCalculatorRegistry}. Adding a
 * metric is one new class: no schema change, since {@code metric_code} is an opaque string,
 * and no engine change, which is the extensibility requirement stated as an
 * interface.
 *
 * <h2>The rules an implementation must keep</h2>
 *
 * <ul>
 *   <li><strong>Read only the context.</strong> No repository, no JPA entity, no web type — a
 *       calculator sees the immutable snapshot and returns value objects. This is
 *       what lets Phase 2 evaluate thousands of candidate configurations without a database.</li>
 *   <li><strong>Be pure and deterministic.</strong> Same snapshot, same numbers. Where a definition
 *       is inherently stochastic — {@code ROBUSTNESS_RANDOM} is the one in this suite — the
 *       randomness has to be pinned, or a variant comparison measures the seed.</li>
 *   <li><strong>Cite the definition.</strong> Each implementation's Javadoc states the metric
 *       definition it implements and the reading it takes of it, so a thesis claim can be traced to
 *       an arithmetic.</li>
 *   <li><strong>Work in periods.</strong> Durations and rates arrive already converted; nothing here
 *       imports {@code TimeUnit}. A time-valued metric says so through
 *       {@link #timeValued()} and the registry attaches the unit.</li>
 * </ul>
 */
public interface MetricCalculator {

    /** The code this produces, e.g. {@code DENSITY}. Unique across all calculators. */
    String code();

    /** Whether this runs on the graph or on simulation traces. */
    MetricKind kind();

    /**
     * The values, in the order they should be presented.
     *
     * @return one entry for a network-scoped metric, or one per element for a scoped one such as
     *         {@code NODE_CRITICALITY}; empty is legitimate where the metric is undefined for this
     *         network, and is not an error
     */
    List<MetricValue> compute(MetricContext ctx);

    /**
     * Whether the value is a count of periods and so needs a unit to be read.
     *
     * <p>Most of the suite is dimensionless — a density, a fill rate, a count — and leaves this
     * false. {@code TTR} is the case it exists for: "TTR = 14" means fourteen days on one network
     * and fourteen hours on another, so the reader cannot be left to guess.
     */
    default boolean timeValued() {
        return false;
    }

    /**
     * Which way is better, for the best-in-row highlighting of the comparison view.
     *
     * <p>Declared here rather than in the comparison service so that adding a metric stays one
     * class: a table of codes over there would be a second list to keep in step with the
     * registry, and the calculator is the only thing that knows what its number means.
     *
     * <p>Defaults to {@link MetricDirection#NEUTRAL}, which is the right answer for a metric that
     * describes a configuration without ranking it — and the safe answer for a new one, since an
     * unranked row shows every value and claims nothing.
     */
    default MetricDirection direction() {
        return MetricDirection.NEUTRAL;
    }
}
