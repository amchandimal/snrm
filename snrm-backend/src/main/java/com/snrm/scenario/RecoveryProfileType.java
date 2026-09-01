package com.snrm.scenario;

/**
 * Shape of the capacity ramp back to nominal after a disruption
 * ({@code DISRUPTION_EVENT.recovery_profile}).
 *
 * <p>This is the persisted <em>discriminator</em>, not the behaviour. The name
 * {@code RecoveryProfile} belongs to the strategy interface returning {@code availability(t)}; each
 * constant here selects one such strategy bean, so the enum is named {@code ...Type} to leave that
 * name free.
 *
 * <p>Persisted as a MySQL {@code ENUM} — keep the literals in step with {@code V2__domain.sql}.
 */
public enum RecoveryProfileType {

    /** Availability stays at the disrupted level for the whole duration, then snaps back. */
    STEP,

    /** Availability ramps back linearly across the recovery window. */
    LINEAR,

    /** Availability approaches nominal asymptotically. */
    EXPONENTIAL
}
