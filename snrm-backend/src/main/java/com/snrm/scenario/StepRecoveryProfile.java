package com.snrm.scenario;

import org.springframework.stereotype.Component;

/**
 * {@code STEP} — "full capacity returns after duration".
 *
 * <pre>
 *   availability(e) = 1 − severity      for 0 ≤ e &lt; window
 *                   = 1                 thereafter
 * </pre>
 *
 * <p>The element is held at its disrupted level for the whole window and snaps back the moment it
 * ends. No recovery process is modelled at all, which is the point: this is the profile for a
 * disruption whose end is an event rather than a process — a border reopening, a strike settling, a
 * supplier's allocation being restored — and it is the honest default when nothing is known about
 * how a facility comes back.
 *
 * <p>It is also the profile the metrics are easiest to reason about under, because the
 * performance curve it produces is a rectangle: {@code TTR} is the window, {@code LOSS_AREA} is
 * width × depth, and both can be checked by hand. {@code docs/simulation-verification.md} uses it
 * for exactly that reason.
 *
 * <p><strong>Source.</strong> The step form is the disruption model Ivanov et al. (2016) use for
 * recovery timing in supply-chain simulation, and the one the RQ4 synthesis records as the most
 * common treatment of disruption duration in the reviewed corpus.
 */
@Component
public class StepRecoveryProfile implements RecoveryProfile {

    @Override
    public RecoveryProfileType type() {
        return RecoveryProfileType.STEP;
    }

    @Override
    public double availability(long elapsedPeriods, long windowPeriods, double severity) {
        // Flat across the window; the boundary cases are RecoveryProfile.availabilityAt's.
        return 1 - severity;
    }
}
