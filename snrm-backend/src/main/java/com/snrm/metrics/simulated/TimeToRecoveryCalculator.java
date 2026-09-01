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
 * {@code TTR} — the recovery family.
 *
 * <blockquote>"Periods from disruption onset until fill rate regains its pre-disruption baseline;
 * reported in periods and in the network's period unit."</blockquote>
 *
 * <pre>
 *   TTR(replication) = min { p ≥ onset : fill(p) ≥ baselineFill(p) } − onset
 * </pre>
 *
 * <h2>Three readings this definition needs, and the reason for each</h2>
 *
 * <p><strong>"Its pre-disruption baseline" is the paired baseline replication, period by period —
 * not the average of the periods before onset.</strong> A network with lead times ramps up from its
 * opening inventory (see {@code SimulationEngine}), and a scenario whose event fires in period 0 has
 * no pre-disruption periods to average at all. Comparing against the undisrupted twin — which shares
 * this replication's demand realisation and its random outages, by construction (see
 * {@code ReplicationRng}) — makes the comparison exact in both cases: the two horizons differ in the
 * disruption and in nothing else.
 *
 * <p><strong>A replication in which no event occurred contributes no observation.</strong> Events
 * carry a probability, so with {@code probability < 1} some replications are simply not
 * disrupted. Such a replication has nothing to recover from; recording it as {@code TTR = 0} would
 * pull the mean towards zero in proportion to the event's improbability and make a rare-but-slow
 * disruption look fast. If no replication was disrupted, the metric produces no value at all.
 *
 * <p><strong>A replication that never recovers is censored at the horizon</strong> and contributes
 * {@code horizon − onset}. That is a lower bound on its true recovery time, not the value, and it
 * biases the mean <em>downwards</em> — the opposite of the direction a resilience claim would want
 * to err in, and therefore the safe one. The alternative, dropping such replications, biases the
 * mean downwards far harder by discarding exactly the worst cases. Where censoring is common the
 * remedy is a longer horizon, and {@code MIN_FILL_RATE} beside a censored {@code TTR} is what
 * reveals it. {@code docs/simulation-verification.md} states this reading explicitly.
 *
 * <p>Time-valued, so {@link #timeValued()} is true and the registry attaches the network's period
 * unit — "TTR = 14 periods (14 days)" rather than a bare 14.
 *
 * <p><strong>Source.</strong> Time-to-recovery is the recovery-family capture mechanism of the RQ5
 * synthesis, following Ivanov et al. (2016) on recovery modelling.
 */
@Component
@Order(130)
public class TimeToRecoveryCalculator implements MetricCalculator {

    public static final String CODE = "TTR";

    /**
     * How close to the baseline counts as recovered.
     *
     * <p>Absolute, on a fill rate that is itself in {@code [0,1]}, and far below the residual of the
     * fixed-point allocation grid. Without it, a period that matches its baseline exactly can fail
     * the comparison by one bit of floating-point summation and push the recovery a period later —
     * or, where the baseline is never quite regained, censor the whole replication.
     */
    private static final double TOLERANCE = 1e-9;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#LOWER_IS_BETTER} — recovering sooner is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public boolean timeValued() {
        return true;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        SimulationTraces traces = ctx.requireTraces();
        if (traces.replications() == 0 || traces.baseline().isEmpty()) {
            // The baseline run of FR-17: the pairing was skipped, so there is no twin to measure
            // recovery against — and nothing was disrupted to recover from. The same guard the
            // other three disruption-relative calculators carry; without it, paired(i) below
            // would reach into an empty list before the onset check could skip the replication.
            return List.of();
        }
        double[] observations = new double[traces.replications()];
        int produced = 0;

        for (int i = 0; i < traces.replications(); i++) {
            SimulationTraces.Pair pair = traces.paired(i);
            OptionalInt onset = pair.onset();
            if (onset.isEmpty()) {
                continue;
            }
            int from = onset.getAsInt();
            int horizon = pair.disrupted().horizonPeriods();
            // Censored at the horizon unless a recovery period is found below.
            int recoveredAt = horizon;
            for (int t = from; t < horizon; t++) {
                double disrupted = pair.disrupted().period(t).fillRate();
                double baseline = pair.baseline().period(t).fillRate();
                if (disrupted >= baseline - TOLERANCE) {
                    recoveredAt = t;
                    break;
                }
            }
            observations[produced++] = recoveredAt - from;
        }

        if (produced == 0) {
            return List.of();
        }
        return ReplicationStatistics.summarise(CODE, Arrays.copyOf(observations, produced));
    }
}
