package com.snrm.metrics;

import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import com.snrm.network.GraphFixtures;
import com.snrm.network.NetworkGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry — "the registry runs all applicable calculators".
 *
 * <p>Stub calculators rather than the real suite: what is under test is the dispatch, the duplicate
 * check and the one write the registry makes to a value (the display unit). The real
 * calculators are verified against hand arithmetic in {@link VerificationNetworkTest}.
 */
class MetricCalculatorRegistryTest {

    private static final NetworkGraph GRAPH = GraphFixtures.verificationNetwork();

    @Test
    @DisplayName("it runs every calculator of the requested kind, in list order")
    void runsCalculatorsOfOneKind() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(List.of(
                topological("A", 1),
                topological("B", 2),
                simulated("C", 3)));

        List<MetricValue> values = registry.compute(MetricKind.TOPOLOGICAL, context());

        assertThat(values).extracting(MetricValue::code).containsExactly("A", "B");
        assertThat(values).extracting(MetricValue::value).containsExactly(1.0, 2.0);
    }

    @Test
    @DisplayName("calculators of the other kind are not run at all")
    void doesNotRunTheOtherKind() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(List.of(
                topological("A", 1),
                new Stub("BOOM", MetricKind.SIMULATED, false, ctx -> {
                    throw new IllegalStateException("a simulated calculator must not run here");
                })));

        assertThat(registry.compute(MetricKind.TOPOLOGICAL, context()))
                .extracting(MetricValue::code).containsExactly("A");
    }

    @Test
    @DisplayName("a kind with no calculators computes nothing rather than failing")
    void emptyKindIsEmpty() {
        MetricCalculatorRegistry registry =
                new MetricCalculatorRegistry(List.of(topological("A", 1)));

        assertThat(registry.of(MetricKind.SIMULATED)).isEmpty();
        assertThat(registry.compute(MetricKind.SIMULATED, context())).isEmpty();
    }

    @Test
    @DisplayName("two calculators claiming one code are refused at startup, not at request time")
    void duplicateCodesAreRefused() {
        // A metric code is the client's only handle on a value. Two rows a client cannot
        // tell apart is a failure worth finding when the context starts rather than in a results
        // table months later.
        assertThatThrownBy(() -> new MetricCalculatorRegistry(List.of(
                topological("DENSITY", 1),
                topological("DENSITY", 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DENSITY")
                .hasMessageContaining(Stub.class.getName());
    }

    @Test
    @DisplayName("the same code across the two kinds is still a duplicate")
    void duplicateAcrossKindsIsAlsoRefused() {
        assertThatThrownBy(() -> new MetricCalculatorRegistry(List.of(
                topological("TTR", 1),
                simulated("TTR", 2))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a time-valued metric is stamped with the network's period unit")
    void attachesTheDisplayUnitToTimeValuedMetrics() {
        // "TTR = 14" means fourteen hours on this network and would mean fourteen days on another.
        // The unit belongs to the clock, not to the metric, so the registry attaches it and the
        // calculator never imports TimeUnit.
        NetworkGraph hourly = GraphFixtures.network()
                .clock(1, TimeUnit.HOUR, RoundingPolicy.NEAREST)
                .supplier("SUP-1").customer("CUST-1", 10)
                .link("SUP-1", "CUST-1")
                .build();
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(List.of(
                new Stub("TTR", MetricKind.TOPOLOGICAL, true,
                        ctx -> List.of(MetricValue.network("TTR", 14))),
                topological("DENSITY", 1)));

        List<MetricValue> values = registry.compute(MetricKind.TOPOLOGICAL,
                MetricContext.of(hourly));

        assertThat(values.get(0).displayUnit()).isEqualTo(TimeUnit.HOUR);
        // Everything else is dimensionless and must stay so: a unit on a density would be a lie.
        assertThat(values.get(1).displayUnit()).isNull();
    }

    @Test
    @DisplayName("a calculator that throws fails the whole suite, naming itself and the network")
    void aFailingCalculatorFailsTheComputation() {
        // Not skipped. The suite exists so configurations are judged on a common basis (RQ5);
        // a suite with a silent hole in it would be compared against a complete one.
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(List.of(
                topological("A", 1),
                new Stub("BROKEN", MetricKind.TOPOLOGICAL, false, ctx -> {
                    throw new ArithmeticException("divided by the number of nodes");
                })));

        assertThatThrownBy(() -> registry.compute(MetricKind.TOPOLOGICAL, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROKEN")
                .hasMessageContaining("network " + GRAPH.networkId())
                .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("the calculator list it hands out cannot be edited")
    void exposedListIsUnmodifiable() {
        MetricCalculatorRegistry registry =
                new MetricCalculatorRegistry(List.of(topological("A", 1)));

        assertThatThrownBy(() -> registry.of(MetricKind.TOPOLOGICAL).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------- helpers

    private static MetricContext context() {
        return MetricContext.of(GRAPH);
    }

    private static MetricCalculator topological(String code, double value) {
        return new Stub(code, MetricKind.TOPOLOGICAL, false,
                ctx -> List.of(MetricValue.network(code, value)));
    }

    private static MetricCalculator simulated(String code, double value) {
        return new Stub(code, MetricKind.SIMULATED, false,
                ctx -> List.of(MetricValue.network(code, value)));
    }

    /** A calculator whose whole behaviour is supplied at the call site. */
    private record Stub(String code, MetricKind kind, boolean timeValued,
            Function<MetricContext, List<MetricValue>> body) implements MetricCalculator {

        @Override
        public List<MetricValue> compute(MetricContext ctx) {
            return body.apply(ctx);
        }
    }
}
