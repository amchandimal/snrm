package com.snrm.metrics;

import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@link MetricCalculator} is allowed to see, and the shared-derivation cache.
 *
 * <p>The cache is the reason the topological suite meets FR-04 at all: {@code NODE_CRITICALITY}
 * costs one maximum-flow computation per node and {@code ROBUSTNESS_TARGETED} needs the same
 * ranking, so computing it twice would double the most expensive thing in the suite.
 */
class MetricContextTest {

    private static final Object KEY = new Object();
    private static final Object OTHER_KEY = new Object();

    @Test
    @DisplayName("it carries the snapshot, and nothing else is needed to compute a topological metric")
    void carriesTheSnapshot() {
        NetworkGraph graph = GraphFixtures.verificationNetwork();

        assertThat(MetricContext.of(graph).graph()).isSameAs(graph);
    }

    @Test
    @DisplayName("a derivation runs once per context, however many calculators ask for it")
    void derivationRunsOnce() {
        MetricContext ctx = MetricContext.of(GraphFixtures.verificationNetwork());
        AtomicInteger runs = new AtomicInteger();

        String first = ctx.derived(KEY, () -> {
            runs.incrementAndGet();
            return "computed";
        });
        String second = ctx.derived(KEY, () -> {
            runs.incrementAndGet();
            return "recomputed";
        });

        assertThat(runs).hasValue(1);
        assertThat(first).isEqualTo("computed");
        // The second supplier is never invoked — the cache answers, and a key therefore has to name
        // exactly one computation. That is why keys are constants declared beside the code that
        // computes them.
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("different keys are independent")
    void keysAreIndependent() {
        MetricContext ctx = MetricContext.of(GraphFixtures.verificationNetwork());

        assertThat(ctx.<String>derived(KEY, () -> "a")).isEqualTo("a");
        assertThat(ctx.<String>derived(OTHER_KEY, () -> "b")).isEqualTo("b");
        assertThat(ctx.<String>derived(KEY, () -> "c")).isEqualTo("a");
    }

    @Test
    @DisplayName("a fresh context caches nothing from the last one")
    void cacheIsPerComputation() {
        // A snapshot is taken once per computation and never refreshed; the cache must
        // have the same lifetime, or a second request would answer from the first one's graph.
        NetworkGraph graph = GraphFixtures.verificationNetwork();

        assertThat(MetricContext.of(graph).<String>derived(KEY, () -> "first")).isEqualTo("first");
        assertThat(MetricContext.of(graph).<String>derived(KEY, () -> "second")).isEqualTo("second");
    }
}
