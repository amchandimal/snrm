package com.snrm.metrics.simulated;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import com.snrm.simulation.SimulationTraces;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * {@code RESILIENCE_INDEX} — the composite family, and the default objective of the
 * Phase 2 search.
 *
 * <blockquote>"Mean performance during the disruption horizon ÷ undisrupted performance
 * (0–1)."</blockquote>
 *
 * <pre>
 *                        mean over t ∈ [onset, H) of  disruptedFill(t)
 *   RESILIENCE_INDEX = ───────────────────────────────────────────────      clamped to [0,1]
 *                        mean over t ∈ [onset, H) of  baselineFill(t)
 * </pre>
 *
 * <h2>The three readings the definition forces</h2>
 *
 * <p><strong>"The disruption horizon" is from onset to the end of the run</strong>, not the event's
 * own window. A disruption that is absorbed instantly and one that leaves the network short for
 * twenty periods afterwards would score identically if the window were the denominator, and the
 * whole point of a composite index is that it should not. Measuring to the end of the horizon makes
 * the index sensitive to the recovery as well as to the impact, which is what distinguishes it from
 * {@code MIN_FILL_RATE}.
 *
 * <p><strong>The denominator is the paired baseline over the same window</strong>, not 1. On a
 * network with lead times the undisrupted run does not itself achieve a fill rate of 1 in every
 * period (see {@code SimulationEngine} on warm-up), and dividing by 1 would charge the disruption
 * for the network's ordinary behaviour. Dividing by the twin that shares this replication's demand
 * and outages leaves the ratio measuring the disruption alone — and makes the index exactly 1 for a
 * replication in which nothing happened, which is the reading the metric's "(0–1)" range implies.
 *
 * <p><strong>A replication with no disruption scores 1</strong> rather than being dropped. This is
 * the opposite of {@code TTR}'s treatment of the same case, and deliberately so: there is no
 * recovery time when there was nothing to recover from, but there <em>is</em> a ratio of performance
 * to undisrupted performance, and it is one. Dropping such replications would make the index
 * conditional on the disruption occurring, and a scenario with a 10% event probability would report
 * the index of the 10% — hiding precisely the resilience that comes from a disruption being
 * unlikely.
 *
 * <p><strong>Clamped to {@code [0,1]}</strong> because that is the defined range. With demand noise
 * a disrupted replication can out-perform its twin in some periods and produce a ratio slightly
 * above 1; a composite index that reported 1.004 would invite the reading that the disruption helped.
 *
 * <p><strong>Source.</strong> The Resilience Index is the RQ5 composite-family capture mechanism,
 * following Dorneanu et al. (2023).
 */
@Component
@Order(180)
public class ResilienceIndexCalculator implements MetricCalculator {

    public static final String CODE = "RESILIENCE_INDEX";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#HIGHER_IS_BETTER} — closer to undisrupted performance is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.HIGHER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        SimulationTraces traces = ctx.requireTraces();
        if (traces.replications() == 0 || traces.baseline().isEmpty()) {
            return List.of();
        }
        double[] observations = new double[traces.replications()];
        int produced = 0;

        for (int i = 0; i < traces.replications(); i++) {
            SimulationTraces.Pair pair = traces.paired(i);
            OptionalInt onset = pair.onset();
            if (onset.isEmpty()) {
                // Nothing happened: performance is undisrupted performance, so the ratio is 1.
                observations[produced++] = 1;
                continue;
            }
            int from = onset.getAsInt();
            int horizon = Math.min(pair.disrupted().horizonPeriods(),
                    pair.baseline().horizonPeriods());
            double disruptedSum = 0;
            double baselineSum = 0;
            int counted = 0;
            for (int t = from; t < horizon; t++) {
                disruptedSum += pair.disrupted().period(t).fillRate();
                baselineSum += pair.baseline().period(t).fillRate();
                counted++;
            }
            if (counted == 0) {
                // An onset in the last period leaves no window; the run says nothing about
                // recovery, so it contributes nothing rather than a fabricated ratio.
                continue;
            }
            // Both sums are over the same count, so the means' ratio is the sums' ratio. A baseline
            // that served nothing over the window makes the ratio undefined; 1 is the only
            // non-arbitrary reading — the disruption cannot be blamed for a performance the intact
            // network did not achieve either.
            observations[produced++] = baselineSum <= 0 ? 1 : disruptedSum / baselineSum;
        }

        if (produced == 0) {
            return List.of();
        }
        return ReplicationStatistics.summariseBounded(CODE, Arrays.copyOf(observations, produced),
                0, 1);
    }
}
