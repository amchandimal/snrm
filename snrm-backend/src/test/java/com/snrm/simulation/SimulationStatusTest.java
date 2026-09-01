package com.snrm.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two status policies, and the fact that they are different questions (FR-20).
 *
 * <p>Needs no database, no Spring and no Docker, like every other policy test in this module. What
 * it pins is the one line of FR-20 that is easiest to get wrong by making the enum tidier:
 * <strong>{@code DONE} freezes its network and is nevertheless deletable.</strong> Collapsing the
 * two predicates into one — "locking implies undeletable" — would remove the exit the whole feature
 * exists to provide, and would do it silently, because every other status agrees.
 */
@DisplayName("SimulationStatus — the freeze and the refusal are different questions (FR-20)")
class SimulationStatusTest {

    @Test
    @DisplayName("QUEUED, RUNNING and DONE freeze the network; FAILED and CANCELLED do not")
    void networkLockingIsUnchanged() {
        assertThat(SimulationStatus.QUEUED.isNetworkLocking()).isTrue();
        assertThat(SimulationStatus.RUNNING.isNetworkLocking()).isTrue();
        assertThat(SimulationStatus.DONE.isNetworkLocking()).isTrue();
        assertThat(SimulationStatus.FAILED.isNetworkLocking()).isFalse();
        assertThat(SimulationStatus.CANCELLED.isNetworkLocking()).isFalse();

        assertThat(SimulationStatus.networkLocking())
                .containsExactlyInAnyOrder(SimulationStatus.QUEUED, SimulationStatus.RUNNING,
                        SimulationStatus.DONE);
    }

    @Test
    @DisplayName("only QUEUED and RUNNING refuse deletion — a job still owns them")
    void activeIsTheTwoStatesAJobOwns() {
        assertThat(SimulationStatus.QUEUED.isActive()).isTrue();
        assertThat(SimulationStatus.RUNNING.isActive()).isTrue();
        assertThat(SimulationStatus.DONE.isActive()).isFalse();
        assertThat(SimulationStatus.FAILED.isActive()).isFalse();
        assertThat(SimulationStatus.CANCELLED.isActive()).isFalse();

        assertThat(SimulationStatus.active())
                .containsExactlyInAnyOrder(SimulationStatus.QUEUED, SimulationStatus.RUNNING);
    }

    @Test
    @DisplayName("DONE is the one status that freezes and still deletes — that IS FR-20")
    void doneFreezesAndDeletes() {
        // The single assertion the feature turns on. A DONE run holds its network frozen
        // and can be deleted anyway, which is what makes deletion the freeze's exit rather than an
        // exemption from it. If this ever fails, the network-editor's "discard runs and edit in
        // place" has silently become a no-op on exactly the runs it exists for.
        assertThat(SimulationStatus.DONE.isNetworkLocking()).isTrue();
        assertThat(SimulationStatus.DONE.isActive()).isFalse();

        // And the converse, so the two sets cannot quietly become the same set: everything active is
        // locking, but not everything locking is active.
        assertThat(SimulationStatus.networkLocking()).containsAll(SimulationStatus.active());
        assertThat(SimulationStatus.active()).isNotEqualTo(SimulationStatus.networkLocking());
    }
}
