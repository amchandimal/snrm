package com.snrm.scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers the {@link RecoveryProfile} beans and resolves a
 * {@link RecoveryProfileType} to the strategy that implements it.
 *
 * <p>The same Strategy + registry shape as {@code MetricCalculatorRegistry}, and for the same
 * reason: adding a recovery shape should be one {@code @Component} and an enum constant, with no
 * list to append to and no engine code to touch.
 *
 * <p><strong>Both failures are startup failures.</strong> Two beans claiming one type would make
 * which of them ran depend on classpath order, and a type with no bean would throw in the middle of
 * a Monte Carlo replication — after the run had been accepted, the network frozen, and half the
 * horizon simulated. The enum is a persisted MySQL {@code ENUM}, so its constants are exactly the
 * values a stored event can hold; requiring one bean each is what makes "every stored event can be
 * simulated" true when the application starts rather than when a researcher submits a run.
 */
@Component
public class RecoveryProfiles {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProfiles.class);

    private final Map<RecoveryProfileType, RecoveryProfile> byType =
            new EnumMap<>(RecoveryProfileType.class);

    RecoveryProfiles(List<RecoveryProfile> profiles) {
        for (RecoveryProfile profile : profiles) {
            RecoveryProfile clash = byType.putIfAbsent(profile.type(), profile);
            if (clash != null) {
                throw new IllegalStateException(("Two RecoveryProfile beans claim %s: %s and %s. "
                        + "A recovery profile decides how much capacity a disrupted element has in "
                        + "every period of its window, so which one runs cannot be left "
                        + "to classpath order.")
                        .formatted(profile.type(), clash.getClass().getName(),
                                profile.getClass().getName()));
            }
        }
        for (RecoveryProfileType type : RecoveryProfileType.values()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException(("No RecoveryProfile bean implements %s. The "
                        + "constant is part of the disruption_event.recovery_profile MySQL ENUM, so "
                        + "an event with that profile can already be stored — and would fail "
                        + "mid-replication, after the run had been accepted and the network frozen.")
                        .formatted(type));
            }
        }
        log.info("Recovery profiles: {}", byType.keySet());
    }

    /**
     * The strategy for one profile type.
     *
     * @throws IllegalStateException never in practice — the constructor proves every constant is
     *                               covered — but stated rather than returning null, since a null
     *                               here would surface as a {@code NullPointerException} inside a
     *                               replication with nothing to attribute it to
     */
    public RecoveryProfile of(RecoveryProfileType type) {
        RecoveryProfile profile = byType.get(type);
        if (profile == null) {
            throw new IllegalStateException("No RecoveryProfile for " + type);
        }
        return profile;
    }
}
