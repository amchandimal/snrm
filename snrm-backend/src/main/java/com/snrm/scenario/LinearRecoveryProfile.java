package com.snrm.scenario;

import org.springframework.stereotype.Component;

/**
 * {@code LINEAR} — "capacity ramps back over the recovery window".
 *
 * <pre>
 *   availability(e) = (1 − severity) + severity · (e / window)     for 0 ≤ e &lt; window
 *                   = 1                                            thereafter
 * </pre>
 *
 * <p>A constant repair rate: the same fraction of lost capacity is restored every period. At
 * {@code e = 0} the element is at its full disrupted level, as every profile is; at the last period
 * inside the window it has recovered all but {@code severity / window} of the loss, and the window's
 * end closes that gap. The ramp therefore <em>starts</em> at impact rather than after the outage,
 * which is the reading forced by parameterising the profile by the event's duration and
 * giving it nothing else: the duration is the recovery window, not a delay before one.
 *
 * <p><strong>What choosing this over {@code STEP} costs.</strong> A little over half the loss.
 * Summed across the window,
 *
 * <pre>
 *   Σ(e = 0 … w−1) severity · (1 − e/w)  =  severity · (w + 1) / 2
 * </pre>
 *
 * <p>capacity-periods, against {@code STEP}'s {@code severity · w} — a ratio of {@code (w+1)/(2w)},
 * which is 0.583 over a six-period window and tends to a half as the window lengthens. So a scenario
 * re-run with only the profile changed moves {@code LOSS_AREA} by nearly a factor of two, and that
 * is a modelling choice a thesis should state rather than a detail — which is why the profile is on
 * the event and recorded with it.
 *
 * <p>The {@code +1} rather than {@code −1} is the part worth checking against the code: the sum runs
 * over the periods <em>inside</em> the window, and the first of them is at full severity because
 * every profile agrees about the moment of impact. {@code RecoveryProfileTest} pins the arithmetic.
 *
 * <p>The realistic case is a facility restarting: a plant brings lines back one at a time, a port
 * clears a backlog berth by berth. Where the restoration is a single decision rather than a process,
 * {@link StepRecoveryProfile} is the truthful shape.
 *
 * <p><strong>Source.</strong> Piecewise-linear performance recovery is the standard form of the
 * resilience triangle in the disruption-recovery literature the RQ5 synthesis draws
 * {@code LOSS_AREA} and {@code RESILIENCE_INDEX} from.
 */
@Component
public class LinearRecoveryProfile implements RecoveryProfile {

    @Override
    public RecoveryProfileType type() {
        return RecoveryProfileType.LINEAR;
    }

    @Override
    public double availability(long elapsedPeriods, long windowPeriods, double severity) {
        // windowPeriods > 0 is guaranteed by availabilityAt, which returns 1 for a zero-length
        // window rather than letting this divide.
        double recovered = severity * ((double) elapsedPeriods / windowPeriods);
        return (1 - severity) + recovered;
    }
}
