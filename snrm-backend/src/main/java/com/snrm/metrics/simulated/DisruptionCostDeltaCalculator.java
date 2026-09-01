package com.snrm.metrics.simulated;

import com.snrm.metrics.MetricCalculator;
import com.snrm.metrics.MetricContext;
import com.snrm.metrics.MetricDirection;
import com.snrm.metrics.MetricKind;
import com.snrm.metrics.MetricValue;
import com.snrm.simulation.SimulationTraces;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code DISRUPTION_COST_DELTA} — the economic family.
 *
 * <blockquote>"cost increase attributable to the disruption vs. the undisrupted baseline run"
 * </blockquote>
 *
 * <pre>
 *   DISRUPTION_COST_DELTA(i) = TOTAL_COST(disrupted i) − TOTAL_COST(baseline i)
 * </pre>
 *
 * <h2>A paired difference, and what that buys</h2>
 *
 * <p>The obvious implementation is mean cost with the disruption minus mean cost without it. It
 * gives the same expectation and a far wider confidence interval, because the variance of a
 * difference of independent means is the <em>sum</em> of their variances — and demand noise, which
 * is common to both runs, would be counted twice.
 *
 * <p>Here the two are the same replication: baseline replication <em>i</em> and disrupted
 * replication <em>i</em> share a seed address and therefore the same demand realisation and the same
 * random outages ({@code ReplicationRng}). Their difference cancels everything the two
 * horizons have in common and leaves what the disruption did. The interval is correspondingly
 * tighter — often several-fold — for the same replication count, which is real compute saved rather
 * than a statistical nicety.
 *
 * <p><strong>The sign is informative.</strong> A negative delta is possible and is not a defect: a
 * disruption that stops material moving saves the variable and transport cost of moving it. That the
 * delta is nevertheless positive on any sensibly parameterised network is the shortage term of
 * {@code TOTAL_COST} doing its job — and a run that reports a negative delta is telling the reader
 * that its {@code product.unit_value} figures are too low to represent what a lost sale costs, which
 * is a data problem worth surfacing rather than hiding.
 *
 * <p><strong>Where {@link SimulationParams} sets {@code baselineSuppressesFailures}</strong>, the
 * baseline is a network in which nothing at all goes wrong, and the delta then measures the
 * scenario's disruption <em>plus</em> the network's own unreliability. That is a legitimate question
 * and a different one; the default leaves random failures in both sets so the delta isolates the
 * scenario.
 */
@Component
@Order(160)
public class DisruptionCostDeltaCalculator implements MetricCalculator {

    public static final String CODE = "DISRUPTION_COST_DELTA";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#LOWER_IS_BETTER} — absorbing a disruption more cheaply is a better configuration.
     */
    @Override
    public MetricDirection direction() {
        return MetricDirection.LOWER_IS_BETTER;
    }

    @Override
    public List<MetricValue> compute(MetricContext ctx) {
        SimulationTraces traces = ctx.requireTraces();
        if (traces.replications() == 0 || traces.baseline().isEmpty()) {
            return List.of();
        }
        double[] observations = new double[traces.replications()];
        for (int i = 0; i < traces.replications(); i++) {
            SimulationTraces.Pair pair = traces.paired(i);
            observations[i] = pair.disrupted().totalCost() - pair.baseline().totalCost();
        }
        return ReplicationStatistics.summarise(CODE, observations);
    }
}
