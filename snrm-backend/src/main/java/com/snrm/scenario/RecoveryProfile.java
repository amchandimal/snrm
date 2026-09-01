package com.snrm.scenario;

/**
 * The shape of a disrupted element's capacity as it comes back — a strategy returning
 * {@code availability(t)}.
 *
 * <blockquote>"{@code STEP} (full capacity returns after duration), {@code LINEAR} (capacity ramps
 * back over the recovery window), {@code EXPONENTIAL} (asymptotic ramp)."</blockquote>
 *
 * <p>Implementations are Spring beans discovered by {@link RecoveryProfiles}, one per constant of
 * {@link RecoveryProfileType}. A fourth shape — an S-curve, a two-stage restart — is a new
 * {@code @Component} and a new enum constant, with no change to the simulation engine: extension
 * happens through the SPI.
 *
 * <h2>The contract</h2>
 *
 * <ul>
 *   <li><strong>Pure.</strong> No state, no randomness, no clock. The same three arguments give the
 *       same answer on every replication and every thread — a profile bean is shared across the
 *       hundreds of virtual threads without synchronisation, so a field would be a bug.
 *       Where an event's timing is uncertain, the jitter is drawn once per replication and reaches
 *       the profile as a different {@code windowPeriods}; it is never drawn here.</li>
 *   <li><strong>Availability, not loss.</strong> The return value is a multiplier on nominal
 *       capacity in {@code [0,1]}: 1 is untouched, 0 is offline. {@code severity} arrives as a
 *       capacity-availability multiplier reduction in [0,1], so a severity of 1.0
 *       takes the target fully offline and the profile's job is to say how it comes back.</li>
 *   <li><strong>The window is closed at its start and open at its end.</strong> At
 *       {@code elapsed = 0} every profile returns exactly {@code 1 − severity}: that is the moment of
 *       impact, and three profiles that disagreed about it would make the choice of profile change
 *       the depth of the disruption rather than its recovery. At {@code elapsed ≥ windowPeriods}
 *       every profile returns exactly 1. What happens in between is the whole of what distinguishes
 *       them.</li>
 * </ul>
 *
 * <p>Callers never invoke {@link #availability} outside the window; {@link #availabilityAt} is the
 * guarded form the engine uses, and it is a default method so no implementation has to repeat the
 * two boundary cases.
 */
public interface RecoveryProfile {

    /** Which {@code disruption_event.recovery_profile} value selects this strategy. */
    RecoveryProfileType type();

    /**
     * The availability multiplier {@code elapsedPeriods} into the disruption.
     *
     * <p>Only called with {@code 0 ≤ elapsedPeriods < windowPeriods} and {@code windowPeriods ≥ 1};
     * {@link #availabilityAt} handles everything outside that.
     *
     * @param elapsedPeriods whole periods since the event fired
     * @param windowPeriods  the event's duration in whole periods, which is what parameterises the
     *                       recovery
     * @param severity       the capacity-availability multiplier reduction in {@code [0,1]}
     * @return the multiplier on nominal capacity, in {@code [0,1]}
     */
    double availability(long elapsedPeriods, long windowPeriods, double severity);

    /**
     * The guarded form: the multiplier at any elapsed offset, including outside the window.
     *
     * <p>Before the event and once the window has passed, availability is exactly 1 — not
     * "approximately 1". {@link RecoveryProfileType#EXPONENTIAL} is why this matters: an asymptotic
     * ramp never reaches its limit, and letting it trail off at 0.9985 for the rest of the horizon
     * would put a permanent, invisible capacity haircut on every network that used it and make
     * {@code TTR} unmeasurable, since fill rate would never quite regain its baseline.
     * The window's end is the recovery, by definition; the profile decides the path to it.
     *
     * <p>A zero-length window is likewise fully recovered at once. {@code ck_event_window} and
     * {@code DisruptionScenarioService} both refuse an event that lasts no time, but a duration
     * shorter than the network's period can still round to zero, and the engine is
     * entitled to a number rather than a division by zero.
     */
    default double availabilityAt(long elapsedPeriods, long windowPeriods, double severity) {
        if (elapsedPeriods < 0 || elapsedPeriods >= windowPeriods || windowPeriods <= 0) {
            return 1;
        }
        double available = availability(elapsedPeriods, windowPeriods, severity);
        return Math.min(1, Math.max(0, available));
    }
}
