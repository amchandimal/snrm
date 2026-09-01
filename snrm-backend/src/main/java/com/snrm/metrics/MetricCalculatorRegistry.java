package com.snrm.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry half of the Strategy + registry pattern.
 *
 * <blockquote>"The registry runs all applicable calculators and persists MetricValue rows with
 * CIs."</blockquote>
 *
 * <p>It runs them; persistence is {@code TopologicalMetricsService}'s, so the registry itself stays
 * on the engine side and can be called by the Phase 2 configuration engine to score a
 * candidate that will never be written anywhere.
 *
 * <p><strong>Discovery, not registration.</strong> Spring injects every {@link MetricCalculator}
 * bean on the classpath, so a new metric is a new {@code @Component} and nothing else — no list to
 * append to, which is exactly the file a contributor forgets.
 *
 * <p><strong>Order is the calculator's own.</strong> Spring sorts an injected list by {@code @Order}
 * / {@link org.springframework.core.Ordered}, so each calculator declares where it sits in the suite
 * and one without an order lands at the end. The order is presentational only: no calculator may
 * depend on another having run (see {@link MetricContext#derived}).
 *
 * <p>Duplicate codes are refused at startup. Two calculators claiming {@code DENSITY} would write
 * two rows a client cannot tell apart, and finding that out at startup beats finding it out in a
 * results table.
 */
@Component
public class MetricCalculatorRegistry {

    private static final Logger log = LoggerFactory.getLogger(MetricCalculatorRegistry.class);

    private final Map<MetricKind, List<MetricCalculator>> byKind = new EnumMap<>(MetricKind.class);

    /**
     * Every calculator by its code, in suite order — what lets a reader of a persisted
     * {@code metric_result} row recover the metric's direction and time-valuedness.
     *
     * <p>The comparison view needs both, and neither is on the row: {@code metric_result} is
     * deliberately narrow, so a stored value knows its code and nothing about what the
     * code means. Asking the registry keeps that knowledge in the one place that already has it —
     * the calculator — rather than in a second table of codes beside the comparison service, which
     * is exactly the file that goes stale when a metric is added.
     */
    private final Map<String, MetricCalculator> byCode;

    MetricCalculatorRegistry(List<MetricCalculator> calculators) {
        Map<String, MetricCalculator> byCode = new LinkedHashMap<>();
        for (MetricKind kind : MetricKind.values()) {
            byKind.put(kind, new ArrayList<>());
        }
        for (MetricCalculator calculator : calculators) {
            MetricCalculator clash = byCode.putIfAbsent(calculator.code(), calculator);
            if (clash != null) {
                throw new IllegalStateException(("Two MetricCalculator beans claim the code '%s': "
                        + "%s and %s. A metric code is the client's only handle on a value, "
                        + "so it has to be unique.")
                        .formatted(calculator.code(), clash.getClass().getName(),
                                calculator.getClass().getName()));
            }
            byKind.get(calculator.kind()).add(calculator);
        }
        byKind.replaceAll((kind, found) -> Collections.unmodifiableList(found));
        this.byCode = Collections.unmodifiableMap(byCode);
        log.info("Metric suite: {} topological, {} simulated calculator(s) — {}",
                byKind.get(MetricKind.TOPOLOGICAL).size(), byKind.get(MetricKind.SIMULATED).size(),
                byCode.keySet());
    }

    /** The calculators of one kind, in suite order. */
    public List<MetricCalculator> of(MetricKind kind) {
        return byKind.getOrDefault(kind, List.of());
    }

    /**
     * The calculator that produced a persisted value, by its code.
     *
     * <p>Empty for a code no calculator on this classpath claims — a row written by an earlier
     * build, or by a metric since removed. That is not an error: {@code metric_code} is opaque,
     * and a reader that cannot describe a value can still display it.
     */
    public Optional<MetricCalculator> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * Every code in the suite, topological first and then simulated, each family in calculator
     * order — the row order of the comparison matrix.
     *
     * <p>A list rather than a set, because the order <em>is</em> the answer: a matrix that put
     * {@code TOTAL_COST} above {@code FILL_RATE} on one request and below it on the next would be
     * unreadable, and "whatever order the rows came back in" is stable only until someone changes an
     * {@code @Order}.
     */
    public List<String> codesInSuiteOrder() {
        return List.copyOf(byCode.keySet());
    }

    /**
     * Runs every calculator of one kind over one context and collects the values.
     *
     * <p>A calculator that throws takes the whole computation with it rather than being skipped.
     * A metric suite with a hole in it that nothing announces is worse than a failed request: the
     * suite exists so configurations are judged on a common basis (RQ5), and a comparison
     * against a partially-populated suite would be silently unlike the one beside it.
     *
     * @return every value produced, in calculator order, with {@code displayUnit} attached to the
     *         time-valued ones
     */
    public List<MetricValue> compute(MetricKind kind, MetricContext ctx) {
        List<MetricValue> values = new ArrayList<>();
        for (MetricCalculator calculator : of(kind)) {
            List<MetricValue> produced;
            try {
                produced = calculator.compute(ctx);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Metric calculator '%s' (%s) failed on network %d"
                        .formatted(calculator.code(), calculator.getClass().getSimpleName(),
                                ctx.graph().networkId()), failure);
            }
            // Units are attached here and nowhere else: the calculator worked in periods and the
            // unit belongs to the network's clock, not to the metric.
            for (MetricValue value : produced) {
                values.add(calculator.timeValued()
                        ? value.withDisplayUnit(ctx.graph().timeBasis().periodUnit())
                        : value);
            }
        }
        return values;
    }
}
