package com.snrm.scenario;

import org.springframework.stereotype.Component;

/**
 * {@code EXPONENTIAL} — "asymptotic ramp".
 *
 * <pre>
 *   availability(e) = 1 − severity · e^(−k · e / window)     for 0 ≤ e &lt; window,  k = 3
 *                   = 1                                       thereafter
 * </pre>
 *
 * <p>Recovery proportional to what is still lost: fast at first, slowing as the element approaches
 * nominal. This is the shape of a restoration whose remaining work gets harder — the last production
 * line, the last qualified supplier, the last mile of a network — and it is what "asymptotic"
 * means here.
 *
 * <h2>Why k = 3, and why the window still closes exactly</h2>
 *
 * <p>An asymptotic curve has no natural end, but every profile is parameterised by the event's
 * duration and nothing else, so the shape has to be pinned to that window by a constant. {@code k}
 * is the number of time-constants the window spans: at {@code e = window} the residual loss is
 * {@code e^(−k)} of the original, so
 *
 * <pre>
 *   k = 1  →  37% of the loss still outstanding at the window's end
 *   k = 3  →   5%                    ← chosen
 *   k = 5  →  0.7%, visually indistinguishable from STEP's snap-back
 * </pre>
 *
 * <p>Three is the value at which the window means "recovered" in the ordinary sense — the same
 * 95% convention the confidence intervals use — while the curve still spends most of its
 * length visibly below nominal, which is the behaviour that distinguishes this profile from
 * {@link LinearRecoveryProfile} at all.
 *
 * <h2>It is the mildest of the three</h2>
 *
 * <p>Because recovery is front-loaded, this profile spends most of the window closer to nominal than
 * {@link LinearRecoveryProfile} does, and the integrated loss over a window ranks
 * {@code EXPONENTIAL < LINEAR < STEP} — roughly {@code severity·w/3}, {@code severity·(w+1)/2} and
 * {@code severity·w}. Choosing it is therefore the most optimistic of the three readings of the same
 * event, which is worth stating beside a result rather than leaving in the data.
 *
 * <p>It is <em>not</em> uniformly above the linear ramp: on a long window the linear one closes the
 * last few percent faster, and the two cross near the end. What distinguishes the shapes reliably is
 * the increment — this profile's per-period gain strictly decreases, the linear one's is constant —
 * and that is what {@code RecoveryProfileTest} asserts rather than a pointwise comparison that holds
 * only for some window lengths.
 *
 * <p><strong>The last 5% is not left dangling.</strong> {@link RecoveryProfile#availabilityAt}
 * returns exactly 1 from the window's end, so the residual is closed rather than trailed off. That
 * is not a cosmetic decision: an availability of 0.9985 for the rest of the horizon would apply a
 * permanent invisible haircut to the network's capacity, and — worse — would make {@code TTR}
 * unmeasurable, because fill rate would never quite regain its baseline and every replication would
 * report a recovery time censored at the horizon.
 *
 * <p><strong>Source.</strong> Exponential capacity restoration is the recovery form used in the
 * disruption-recovery modelling the RQ4 synthesis reviews, following Ivanov et al. (2016) on
 * recovery dynamics.
 */
@Component
public class ExponentialRecoveryProfile implements RecoveryProfile {

    /**
     * Time-constants spanned by the recovery window. See the class Javadoc: 3 leaves 5% of the loss
     * outstanding at the window's end, which the boundary in {@code availabilityAt} then closes.
     */
    public static final double DECAY_CONSTANTS = 3.0;

    @Override
    public RecoveryProfileType type() {
        return RecoveryProfileType.EXPONENTIAL;
    }

    @Override
    public double availability(long elapsedPeriods, long windowPeriods, double severity) {
        double outstanding = severity
                * Math.exp(-DECAY_CONSTANTS * ((double) elapsedPeriods / windowPeriods));
        return 1 - outstanding;
    }
}
