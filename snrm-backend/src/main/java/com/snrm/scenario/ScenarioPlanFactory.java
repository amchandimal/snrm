package com.snrm.scenario;

import com.snrm.common.TimeBasis;
import com.snrm.network.LinkRepository;
import com.snrm.network.Node;
import com.snrm.network.NodeRepository;
import com.snrm.network.TimeValidationInput.EventWindow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the immutable {@link ScenarioPlan} the simulation engine runs against.
 *
 * <p>The scenario's half of the seam {@code NetworkGraphFactory} owns for the network. On one side:
 * JPA entities, a polymorphic target that no foreign key can express, and durations stated in
 * whatever unit the researcher found natural. On the other: records of primitives and id sets, safe
 * to hand to hundreds of virtual threads, counting periods. Everything in between happens here, once
 * per run.
 *
 * <h2>The three resolutions</h2>
 *
 * <p><strong>Targets become id sets.</strong> A {@code NODE} or {@code LINK} event carries an id that
 * must belong to <em>this</em> network; a {@code REGION} event carries a tag, and this class runs the
 * literal {@code region = ?} query rather than describing it — the same query {@code RegionService}
 * previews with, so what the scenario builder showed and what the run expands the event into cannot
 * differ.
 *
 * <p><strong>An event that resolves to nothing is recorded, not dropped.</strong>
 * {@code DisruptionScenarioService} states the cost it cannot pay: "an event written against the
 * baseline holds baseline node ids, and reconciling that across variants is a question the
 * simulation engine will have to answer." This is that answer. The event is left out of the plan and
 * its id goes into {@link ScenarioPlan#unresolvedEventIds()}, from where {@code SimulationService}
 * refuses the submission. Running it anyway would produce a completed result showing a network
 * shrugging off a disruption it never received.
 *
 * <p><strong>Timing becomes periods, through the one arithmetic that already exists.</strong>
 * {@link EventWindow} is what the editor's resolution banner and the write-time horizon refusal both
 * use; reusing it here is what keeps the timeline, the refusal and the engine from
 * disagreeing about which period an event ends in.
 */
@Component
public class ScenarioPlanFactory {

    private final DisruptionScenarioRepository scenarios;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final RecoveryProfiles profiles;

    ScenarioPlanFactory(DisruptionScenarioRepository scenarios, NodeRepository nodes,
            LinkRepository links, RecoveryProfiles profiles) {
        this.scenarios = scenarios;
        this.nodes = nodes;
        this.links = links;
        this.profiles = profiles;
    }

    /**
     * The scenario as the engine sees it, resolved against one network's contents and clock.
     *
     * @param scenarioId     the scenario to plan
     * @param networkId      the network the run evaluates — targets are resolved in it
     * @param basis          that network's clock, for discretising each event's window
     * @param horizonPeriods that network's horizon, for the check row 4
     * @throws EntityNotFoundException if no such scenario exists
     * @throws EventHorizonException   if an event's window ends after this network's horizon
     */
    @Transactional(readOnly = true)
    public ScenarioPlan plan(long scenarioId, long networkId, TimeBasis basis, int horizonPeriods) {
        DisruptionScenario scenario = scenarios.findWithEventsById(scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("No scenario with id " + scenarioId));

        List<PlannedEvent> planned = new ArrayList<>();
        List<Long> unresolved = new ArrayList<>();
        for (DisruptionEvent event : scenario.getEvents()) {
            checkHorizon(event, networkId, basis, horizonPeriods);
            Resolution resolution = resolve(event, networkId);
            if (resolution.isEmpty()) {
                unresolved.add(event.getId());
                continue;
            }
            planned.add(toPlanned(event, resolution, basis));
        }
        // Start order, so a trace and a log read in the order a researcher drew the bars. Ties keep
        // the repository's own order, which is by canonical second-count.
        planned.sort((a, b) -> Long.compare(a.startPeriod(), b.startPeriod()));

        return new ScenarioPlan(scenarioId, scenario.getName(), networkId, planned, unresolved);
    }

    /**
     * {@code EVENT_EXCEEDS_HORIZON}, against the network this run actually evaluates.
     *
     * <p>{@link DisruptionScenarioService} already refuses an event that overruns a horizon, so this
     * looks redundant — and is not. That check runs against whichever network the researcher was
     * looking at when they drew the bar, and a scenario is project-scoped precisely so it can be
     * replayed against variants, which need not share a period length or a horizon. An
     * event that fits a 52-period daily network overruns a 12-period weekly one, and a run against
     * the second is where that has to be discovered.
     *
     * <p>The arithmetic is {@link EventWindow}'s, the same three lines the editor's banner and the
     * write path use, so none of the three can come to disagree about which period an event ends in.
     * The exception is the same one too — {@code EVENT_EXCEEDS_HORIZON}, deliberately — so a client
     * branches on the rule and not on which endpoint reported it.
     */
    private static void checkHorizon(DisruptionEvent event, long networkId, TimeBasis basis,
            int horizonPeriods) {
        EventWindow window = new EventWindow(event.getId(), null, event.getStartOffset(),
                event.getDuration());
        if (window.exceeds(basis, horizonPeriods)) {
            throw new EventHorizonException(networkId, window.startPeriod(basis),
                    window.windowPeriods(basis), window.endPeriod(basis), horizonPeriods);
        }
    }

    private PlannedEvent toPlanned(DisruptionEvent event, Resolution resolution, TimeBasis basis) {
        // The same three lines TimeValidationService and DisruptionScenarioService run. The offset
        // and the window are discretised separately and never summed first: an offset is the index
        // of the step the event fires in, a duration a count of steps it lasts.
        EventWindow window = new EventWindow(event.getId(), resolution.label(),
                event.getStartOffset(), event.getDuration());
        return new PlannedEvent(
                event.getId(),
                resolution.label(),
                event.getTargetType(),
                resolution.nodeIds(),
                resolution.linkIds(),
                window.startPeriod(basis),
                window.windowPeriods(basis),
                event.getSeverity(),
                profiles.of(event.getRecoveryProfile()),
                event.getProbability());
    }

    /**
     * What one event strikes in this network.
     *
     * <p>A {@code NODE} or {@code LINK} id that belongs to another network resolves to nothing, and
     * is treated exactly like a region tag no node carries: the event is unresolved. That is the
     * cross-variant case — the same scenario replayed against a network cloned from elsewhere — and
     * it must be refused rather than quietly applied to whatever row happens to hold that id.
     */
    private Resolution resolve(DisruptionEvent event, long networkId) {
        Long targetId = event.getTargetId();
        return switch (event.getTargetType()) {
            case NODE -> nodes.findById(targetId == null ? -1L : targetId)
                    .filter(node -> node.getNetwork().getId() == networkId)
                    .map(node -> new Resolution("NODE %d (%s)".formatted(node.getId(), node.getName()),
                            Set.of(node.getId()), Set.of()))
                    .orElse(Resolution.EMPTY);
            case LINK -> targetId != null && links.existsByIdAndNetworkId(targetId, networkId)
                    ? new Resolution("LINK " + targetId, Set.of(), Set.of(targetId))
                    : Resolution.EMPTY;
            case REGION -> resolveRegion(event.getTargetRegion(), networkId);
        };
    }

    private Resolution resolveRegion(String region, long networkId) {
        if (region == null || region.isBlank()) {
            return Resolution.EMPTY;
        }
        List<Node> tagged = nodes.findByNetworkIdAndRegion(networkId, region);
        if (tagged.isEmpty()) {
            return Resolution.EMPTY;
        }
        // Insertion-ordered, so a trace lists a correlated disruption's nodes in a stable order —
        // a set that reordered between runs would make two identical runs' logs diff.
        Set<Long> nodeIds = new LinkedHashSet<>(tagged.size());
        for (Node node : tagged) {
            nodeIds.add(node.getId());
        }
        return new Resolution("REGION %s (%d node%s)"
                .formatted(region, nodeIds.size(), nodeIds.size() == 1 ? "" : "s"),
                nodeIds, Set.of());
    }

    /** What an event's target became in this network; {@link #EMPTY} means it named nothing here. */
    private record Resolution(String label, Set<Long> nodeIds, Set<Long> linkIds) {

        static final Resolution EMPTY = new Resolution(null, Set.of(), Set.of());

        boolean isEmpty() {
            return nodeIds.isEmpty() && linkIds.isEmpty();
        }
    }
}
