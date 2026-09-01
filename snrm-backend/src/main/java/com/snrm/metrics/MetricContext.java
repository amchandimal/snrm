package com.snrm.metrics;

import com.snrm.network.NetworkGraph;
import com.snrm.simulation.SimulationTraces;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Everything a {@link MetricCalculator} is allowed to see.
 *
 * <blockquote>"MetricContext carries the NetworkGraph snapshot and, for SIMULATED metrics, the
 * per-replication SimulationTrace list."</blockquote>
 *
 * <p>Both halves are now present: {@link #graph()} for the topological calculators and
 * {@link #traces()} for the simulated ones. The second was added as one field and no change to the
 * SPI, which was the point of putting a context object in the signature rather than the snapshot
 * itself.
 *
 * <p><strong>The trace list is null for a topological computation</strong>, and a simulated
 * calculator reaches it through {@link #requireTraces()} rather than {@code traces()} so that
 * calling one outside a run fails with a sentence rather than a {@code NullPointerException}. The
 * registry keeps the two kinds apart by {@code MetricKind}, so this is a guard against a
 * programming error, not a branch either kind of calculator has to take.
 *
 * <p><strong>The shared-derivation cache.</strong> Some quantities are wanted by more than one
 * calculator and are far too expensive to compute twice: {@code NODE_CRITICALITY} costs one maximum
 * flow per node, and {@code ROBUSTNESS_TARGETED} needs the same ranking to decide its removal order
 * {@link #derived(Object, Supplier)} memoises such a quantity for the lifetime of one
 * computation.
 *
 * <p>It is a cache, not a dependency mechanism: what is memoised is a <em>pure function of the
 * snapshot</em>, computed on first use by whichever calculator asks first. No calculator reads
 * another's output and none of them can be ordered wrongly, so the registry stays free to run them
 * in any order — the extension guarantee would be worth much less if adding a metric
 * could break an existing one by running before it.
 *
 * <p><strong>Not thread-safe, deliberately.</strong> One context belongs to one computation and the
 * registry walks its calculators sequentially. Parallelism is across <em>replications</em>
 * — each with its own context over the same immutable snapshot — not across calculators.
 */
public final class MetricContext {

    private final NetworkGraph graph;
    private final SimulationTraces traces;
    private final Map<Object, Object> derivations = new HashMap<>();

    private MetricContext(NetworkGraph graph, SimulationTraces traces) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.traces = traces;
    }

    /** A context over one snapshot — the only form a topological computation needs. */
    public static MetricContext of(NetworkGraph graph) {
        return new MetricContext(graph, null);
    }

    /**
     * A context over a snapshot and the replications run against it — what a simulated calculator
     * needs.
     */
    public static MetricContext of(NetworkGraph graph, SimulationTraces traces) {
        return new MetricContext(graph, Objects.requireNonNull(traces, "traces"));
    }

    /** The immutable snapshot: whole periods, per-period rates, no units. */
    public NetworkGraph graph() {
        return graph;
    }

    /** The replication traces, or null for a topological computation. */
    public SimulationTraces traces() {
        return traces;
    }

    /** Whether this context was built for a simulation run. */
    public boolean hasTraces() {
        return traces != null;
    }

    /**
     * The replication traces, or a stated failure.
     *
     * @throws IllegalStateException if this context carries none — which means a {@code SIMULATED}
     *                               calculator was run outside a simulation run, and the registry
     *                               separates the two kinds precisely so that cannot happen
     */
    public SimulationTraces requireTraces() {
        if (traces == null) {
            throw new IllegalStateException(("This MetricContext carries no simulation traces, so "
                    + "only TOPOLOGICAL calculators can run against it. A SIMULATED metric is "
                    + "computed once per simulation job over the replication set; network "
                    + "%d has a snapshot but no run.").formatted(graph.networkId()));
        }
        return traces;
    }

    /**
     * A quantity derived from the snapshot, computed at most once per context.
     *
     * <p><strong>Deliberately not {@code computeIfAbsent}.</strong> Derivations nest: the criticality
     * of {@code ServiceableDemand} is memoised here, and computing it asks for {@code GraphIndex},
     * which is memoised here too. {@code HashMap.computeIfAbsent} detects that the map was modified
     * while its mapping function was running and throws {@link java.util.ConcurrentModificationException}
     * — from a single thread, with nothing concurrent about it. Reading, computing and then putting
     * has no such restriction, and nesting is the normal case here rather than an accident.
     *
     * <p>The cost of the change is that a derivation returning {@code null} is recomputed on every
     * call rather than remembered as absent. No derivation does — each returns a map, an array or a
     * number — and a memoised {@code null} would be indistinguishable from "not computed yet"
     * whatever the implementation.
     *
     * @param key         identity of the derivation; a constant declared beside its computation
     * @param computation run on the first call for this key, ignored afterwards
     * @param <T>         the derived type — the cast is unchecked because the map is heterogeneous,
     *                    and is safe as long as one key always names one computation, which is why
     *                    keys are constants next to the code that computes them
     */
    @SuppressWarnings("unchecked")
    public <T> T derived(Object key, Supplier<T> computation) {
        Object memoised = derivations.get(key);
        if (memoised != null) {
            return (T) memoised;
        }
        T computed = computation.get();
        derivations.put(key, computed);
        return computed;
    }
}
