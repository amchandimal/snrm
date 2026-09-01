package com.snrm.network;

import com.snrm.simulation.SimulationRunRepository;
import com.snrm.simulation.SimulationStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the freeze: the <em>structure</em> of a network referenced by a simulation run is immutable.
 *
 * <p><strong>Why this is a service-level guard and not a database trigger.</strong> The rule is not
 * a data-integrity constraint but a workflow one, and it has a remedy — fork a variant. A trigger
 * can only reject, and it would reject with an opaque SQL error that no client can act on; it would
 * also have to fire on every row of every table beneath a network, and it would be invisible to the
 * editor, which needs to know <em>before</em> the user's first keystroke whether editing will be
 * allowed. Keeping it here means one query, one exception carrying a machine-readable
 * code, and a {@link #isMutable(long)} probe the UI can call up front.
 *
 * <p>Which run states freeze a network is decided once, by
 * {@link SimulationStatus#isNetworkLocking()}.
 *
 * <p><strong>What this guard covers, and the one thing it does not (FR-29).</strong> It
 * covers everything a result was computed from — nodes, links, per-product rows and the time base —
 * and every write path to those must pass through here: {@link NetworkService#setTimeBase} and
 * {@link NetworkService#delete}, {@link NodeService}, {@link LinkService},
 * {@link ProductService}'s node-product writes, and the bulk canvas endpoints. Anything added later
 * that touches structure must too.
 *
 * <p>It does <strong>not</strong> cover a network's <em>name</em> or its <em>baseline flag</em>, and
 * {@link NetworkService#update} is exempt by design rather than by omission.
 * Neither is an input to any metric, and a batch import that names networks after their
 * files makes the network most in need of renaming routinely one that has already been run
 * (FR-28, FR-29); the argument in full is on that method. The exemption is recorded here because a
 * guard whose documentation claims it covers every write, while one path is exempt, is worse than
 * the exemption: the next reader would take the claim as the specification and either re-add the
 * call or trust a guarantee that is not there. {@link NetworkService#clone} is the other unguarded
 * path, and always was — it is the remedy this guard advertises.
 */
@Component
public class NetworkMutationGuard {

    private final SimulationRunRepository simulationRuns;

    public NetworkMutationGuard(SimulationRunRepository simulationRuns) {
        this.simulationRuns = simulationRuns;
    }

    /**
     * Refuses the mutation if any simulation run holds this network frozen.
     *
     * @throws NetworkImmutableException naming the network to fork from
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public void assertMutable(long networkId) {
        if (!isMutable(networkId)) {
            throw new NetworkImmutableException(networkId);
        }
    }

    /**
     * Non-throwing form, for deciding up front whether the editor should offer editing or the fork
     * prompt, and for {@code GET} responses that carry an {@code editable} flag.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isMutable(long networkId) {
        return !simulationRuns.existsByNetworkIdAndStatusIn(networkId, SimulationStatus.networkLocking());
    }
}
