package com.snrm.scenario;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link DisruptionScenario} aggregate root.
 *
 * <p>Events are a collection on the root rather than a separate aggregate: a scenario is only
 * meaningful as a whole, and the simulation engine consumes all of its events at once.
 */
public interface DisruptionScenarioRepository extends JpaRepository<DisruptionScenario, Long> {

    /**
     * A project's scenarios for the sidebar list.
     *
     * <p>Fetches the events even though the list response omits them, because every row shows an
     * event count: without the graph that is one extra select per scenario, and with it the whole
     * list is one query. The alternative — a count projection — would need a second query shape and
     * a second DTO for the sake of a collection that is a handful of rows per scenario.
     */
    @EntityGraph(attributePaths = "events")
    List<DisruptionScenario> findByProjectIdOrderByNameAsc(Long projectId);

    Optional<DisruptionScenario> findByProjectIdAndName(Long projectId, String name);

    boolean existsByProjectIdAndName(Long projectId, String name);

    /** Loads a scenario with its events in one query, for handing to the simulation engine. */
    @EntityGraph(attributePaths = "events")
    Optional<DisruptionScenario> findWithEventsById(Long id);
}
