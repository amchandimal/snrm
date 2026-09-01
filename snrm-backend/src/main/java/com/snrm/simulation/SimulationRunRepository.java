package com.snrm.simulation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link SimulationRun} aggregate root.
 */
public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {

    /**
     * Backs the immutability guard: does any run hold this network frozen?
     *
     * <p>Answered by the {@code ix_run_network_status} covering index, so the check stays cheap
     * enough to sit in front of every network mutation.
     *
     * @see com.snrm.network.NetworkMutationGuard
     * @see SimulationStatus#isNetworkLocking()
     */
    boolean existsByNetworkIdAndStatusIn(Long networkId, Collection<SimulationStatus> statuses);

    List<SimulationRun> findByNetworkIdOrderByIdDesc(Long networkId);

    /**
     * The most recent run of a network in a given state — the column source of the comparison
     * matrix, called with {@link SimulationStatus#DONE}.
     *
     * <p>Most recent by id rather than by {@code finished_at}: ids are assigned at submission and
     * are total, while two runs of the same network can finish in either order and a cancelled one
     * never finishes at all. "The latest run" has to mean the latest a researcher <em>started</em>,
     * which is the order they will look for it in.
     */
    Optional<SimulationRun> findFirstByNetworkIdAndStatusOrderByIdDesc(Long networkId,
            SimulationStatus status);

    /**
     * The same, pinned to one disruption scenario — what {@code ?scenarioId=} on the comparison
     * endpoint resolves to.
     *
     * <p>Pinning it is what makes a comparison a statement about configurations rather than about
     * stories: without it, one column can be the network's behaviour under a plant outage and the
     * next its behaviour under a port closure, and the difference between them attributes to the
     * lever change.
     */
    Optional<SimulationRun> findFirstByNetworkIdAndScenarioIdAndStatusOrderByIdDesc(Long networkId,
            Long scenarioId, SimulationStatus status);

    List<SimulationRun> findByScenarioIdOrderByIdDesc(Long scenarioId);

    List<SimulationRun> findByStatusIn(Collection<SimulationStatus> statuses);
}
