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
 * {@code LOSS_AREA} — the resilience triangle.
 *
 * <blockquote>"area between baseline and disrupted performance curves (resilience triangle)"
 * </blockquote>
 *
 * <pre>
 *   LOSS_AREA(replication) = Σ_t max( 0 , baselineFill(t) − disruptedFill(t) )
 * </pre>
 *
 * <p>Units are <strong>fill-rate · periods</strong>, and the choice is deliberate. The area could
 * equally be taken between the two <em>served-demand</em> curves, in units of demand · periods, and
 * that number would be larger and more concrete. It would also scale with the size of the network,
 * so a variant serving twice the demand would show twice the loss for the same proportional
 * disruption — and comparing configurations is what the tool is for. On the
 * normalised performance curve the metric is dimensionless in its height and comparable across
 * networks, which is the form the resilience-triangle literature uses.
 *
 * <p>An easy figure to read: a disruption that halves performance for six periods has a loss area of
 * 3, whatever the network's size.
 *
 * <p><strong>Only shortfalls count.</strong> The {@code max(0, …)} matters once demand noise is
 * switched on, where a disrupted replication can out-serve its baseline in some period by chance;
 * letting that subtract would net a real loss against an accident of the draw. The area under the
 * baseline is what "loss" means.
 *
 * <p><strong>Measured against the paired baseline replication</strong>, which shares this
 * replication's demand realisation and random outages ({@code ReplicationRng}), so the
 * difference is the disruption's and not two independent samples'. It is computed over the whole
 * horizon rather than from the onset, because a disruption's cost does not stop at its window: a
 * network that empties its buffers absorbing a shock is still short afterwards, and that tail is
 * part of the triangle.
 *
 * <p><strong>Source.</strong> The resilience triangle is the absorption/robustness capture mechanism
 * of the RQ5 synthesis.
 */
@Component
@Order(140)
public class LossAreaCalculator implements MetricCalculator {

    public static final String CODE = "LOSS_AREA";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MetricKind kind() {
        return MetricKind.SIMULATED;
    }

    /**
     * {@link MetricDirection#LOWER_IS_BETTER} — a smaller resilience triangle is a better configuration.
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
            double area = 0;
            int horizon = Math.min(pair.disrupted().horizonPeriods(),
                    pair.baseline().horizonPeriods());
            for (int t = 0; t < horizon; t++) {
                double shortfall = pair.baseline().period(t).fillRate()
                        - pair.disrupted().period(t).fillRate();
                if (shortfall > 0) {
                    area += shortfall;
                }
            }
            observations[i] = area;
        }
        return ReplicationStatistics.summarise(CODE, observations);
    }
}
