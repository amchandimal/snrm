package com.snrm.scenario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link DisruptionEvent}, a child of the {@link DisruptionScenario} aggregate.
 *
 * <p>Exists for the timeline editor, which adds, moves and deletes individual bars;
 * whole-scenario reads go through {@link DisruptionScenarioRepository#findWithEventsById(Long)}.
 */
public interface DisruptionEventRepository extends JpaRepository<DisruptionEvent, Long> {

    /**
     * Events of one scenario in timeline order, over {@code ix_event_window}.
     *
     * <p>Ordered by {@code start_offset_seconds}, the derived column — the stated value is
     * meaningless to sort by once two events in the same scenario are written in different units.
     */
    List<DisruptionEvent> findByScenarioIdOrderByStartOffsetSecondsAsc(Long scenarioId);

    /**
     * Events pointing at a given node or link — the dependency list shown before an editor delete.
     * Over {@code ix_event_target}.
     */
    List<DisruptionEvent> findByTargetTypeAndTargetId(DisruptionTargetType targetType, Long targetId);

    /**
     * Events striking a given region tag, over {@code ix_event_region} (V5).
     *
     * <p>The same dependency list as above, for the case {@code ix_event_target} cannot serve:
     * {@code target_id} is null on every REGION row, so this question would otherwise be a table
     * scan. It matters most here — retagging or deleting the last node of a region silently empties
     * every event that named it, and the event stays in the scenario looking like a disruption.
     */
    List<DisruptionEvent> findByTargetRegion(String targetRegion);
}
