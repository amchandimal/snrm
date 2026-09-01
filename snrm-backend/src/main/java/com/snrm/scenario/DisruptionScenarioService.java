package com.snrm.scenario;

import com.snrm.common.DuplicateNameException;
import com.snrm.common.DurationAmount;
import com.snrm.common.DurationDto;
import com.snrm.common.TimeBasis;
import com.snrm.network.LinkRepository;
import com.snrm.network.Network;
import com.snrm.network.NetworkLookup;
import com.snrm.network.NodeRepository;
import com.snrm.network.TimeValidationInput.EventWindow;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Disruption scenario and event CRUD (FR-05).
 *
 * <p><strong>A scenario belongs to a project; an event is authored against a network.</strong> That
 * asymmetry runs through this class and is the reason every event-writing method takes a
 * {@code networkId}. A scenario is project-scoped so one disruption story can be replayed against
 * every configuration variant, which is what makes the comparison view meaningful —
 * but nothing about an event can be checked without a network:
 *
 * <ul>
 *   <li>its target is a {@code node.id} or {@code link.id}, or a region tag, and all three are
 *       properties of one network's contents;</li>
 *   <li>its window is a pair of real durations, and whether they fit inside the run is a
 *       question about a period length and a horizon.</li>
 * </ul>
 *
 * <p>So {@code networkId} is required on every event write and is <em>not</em> stored: it is the
 * network the researcher is looking at while they draw the bar, and it is where the checks are
 * resolved. Storing it would quietly turn a project-scoped scenario into a network-scoped one.
 * The consequence is honest and worth stating: an event written against the baseline holds node ids
 * from the baseline, so replaying it against a variant that was cloned from something else is a
 * question the simulation engine will have to answer when it lands, not one this service can.
 *
 * <p><strong>Two things this refuses rather than merely reports.</strong> The editor's resolution
 * banner is advisory — a network mid-edit is entitled to be half-built — but an event arrives whole,
 * so {@link EventHorizonException} and {@link EventTargetException} are rejections. The horizon
 * arithmetic itself is shared with the banner, on
 * {@link com.snrm.network.TimeValidationInput.EventWindow}, so the two cannot come to disagree about
 * which period an event ends in.
 *
 * <p>Maps inside the transaction and returns DTOs, like every service here:
 * {@code spring.jpa.open-in-view=false}.
 */
@Service
@Transactional
public class DisruptionScenarioService {

    /** {@code disruption_scenario.name VARCHAR(160)}, and {@code DisruptionScenarioRequest}'s cap. */
    private static final int NAME_LIMIT = 160;

    private final DisruptionScenarioRepository scenarios;
    private final DisruptionEventRepository events;
    private final ProjectRepository projects;
    private final NetworkLookup lookup;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final DisruptionScenarioMapper mapper;

    DisruptionScenarioService(DisruptionScenarioRepository scenarios, DisruptionEventRepository events,
            ProjectRepository projects, NetworkLookup lookup, NodeRepository nodes,
            LinkRepository links, DisruptionScenarioMapper mapper) {
        this.scenarios = scenarios;
        this.events = events;
        this.projects = projects;
        this.lookup = lookup;
        this.nodes = nodes;
        this.links = links;
        this.mapper = mapper;
    }

    // --------------------------------------------------------------------- scenarios

    @Transactional(readOnly = true)
    public List<DisruptionScenarioDto> listByProject(long projectId, long ownerId) {
        requireProject(projectId, ownerId);
        return mapper.toSummaryDtoList(scenarios.findByProjectIdOrderByNameAsc(projectId));
    }

    @Transactional(readOnly = true)
    public DisruptionScenarioDto get(long scenarioId, long ownerId) {
        return mapper.toDto(requireScenario(scenarioId, ownerId));
    }

    /** @throws DuplicateNameException if the project already has a scenario with that name */
    public DisruptionScenarioDto create(long projectId, long ownerId,
            DisruptionScenarioRequest request) {
        Project project = requireProject(projectId, ownerId);
        String name = request.name().trim();
        requireNameFree(projectId, name);

        DisruptionScenario scenario = new DisruptionScenario(project, name);
        applySettings(request, scenario);
        return mapper.toDto(scenarios.save(scenario));
    }

    /**
     * Renames a scenario and replaces its Monte Carlo settings.
     *
     * <p>Not guarded by network immutability: a scenario is not part of any network, and
     * the run that would freeze one records the parameters it actually used, so editing the scenario
     * afterwards cannot rewrite a completed result.
     */
    public DisruptionScenarioDto replace(long scenarioId, long ownerId,
            DisruptionScenarioRequest request) {
        DisruptionScenario scenario = requireScenario(scenarioId, ownerId);
        String name = request.name().trim();
        if (!scenario.getName().equals(name)) {
            requireNameFree(scenario.getProject().getId(), name);
        }
        scenario.setName(name);
        applySettings(request, scenario);
        return mapper.toDto(scenario);
    }

    /**
     * Copies a scenario and every event in it.
     *
     * <p>Deep, and the events are copied through {@link DisruptionEvent#copyInto} so each takes its
     * own {@link DurationAmount} instances — an embeddable is owned by exactly one row, and sharing
     * them is how editing the copy's timing would move the original's bar too.
     *
     * <p>No target is re-checked. The copy holds the same ids and tags as its source and is not
     * authored against any network here, so there is nothing new to resolve; the events were valid
     * against some network when they were written, and they are unchanged.
     */
    public DisruptionScenarioDto duplicate(long scenarioId, long ownerId,
            DuplicateScenarioRequest request) {
        DisruptionScenario source = requireScenario(scenarioId, ownerId);
        long projectId = source.getProject().getId();
        String name = request == null || request.name() == null || request.name().isBlank()
                ? nextCopyName(projectId, source.getName())
                : request.name().trim();
        requireNameFree(projectId, name);

        DisruptionScenario copy = new DisruptionScenario(source.getProject(), name);
        copy.setNumReplications(source.getNumReplications());
        copy.setSeed(source.getSeed());
        for (DisruptionEvent event : source.getEvents()) {
            copy.addEvent(event.copyInto(copy));
        }
        return mapper.toDto(scenarios.save(copy));
    }

    /** Deletes a scenario; its events cascade through {@code fk_event_scenario}. */
    public void delete(long scenarioId, long ownerId) {
        scenarios.delete(requireScenario(scenarioId, ownerId));
    }

    // ------------------------------------------------------------------------ events

    @Transactional(readOnly = true)
    public List<DisruptionEventDto> listEvents(long scenarioId, long ownerId) {
        requireScenario(scenarioId, ownerId);
        return mapper.toEventDtoList(events.findByScenarioIdOrderByStartOffsetSecondsAsc(scenarioId));
    }

    @Transactional(readOnly = true)
    public DisruptionEventDto getEvent(long eventId, long ownerId) {
        return mapper.toDto(requireEvent(eventId, ownerId));
    }

    /**
     * Adds an event to a scenario, resolved and bounds-checked against {@code networkId}.
     *
     * @param networkId the network the event is authored against — its targets are resolved in it
     *                  and its window measured against its clock. Must belong to the scenario's
     *                  project.
     * @throws EventTargetException  if the target does not resolve in that network
     * @throws EventHorizonException if the event's window ends after that network's horizon
     */
    public DisruptionEventDto createEvent(long scenarioId, long networkId, long ownerId,
            DisruptionEventRequest request) {
        DisruptionScenario scenario = requireScenario(scenarioId, ownerId);
        Network network = requireNetworkOfSameProject(networkId, ownerId, scenario);
        check(request, network);

        // The timings are placeholders in the network's own period unit; applyTiming overwrites both
        // with the stated pair. They exist because the columns are NOT NULL and the embeddables are
        // written into rather than replaced.
        DisruptionEvent event = new DisruptionEvent(scenario, request.targetType(),
                request.targetId(), normalisedRegion(request),
                DurationAmount.zero(network.periodUnit()),
                DurationAmount.zero(network.periodUnit()), 0);
        apply(request, event);
        scenario.addEvent(event);
        return mapper.toDto(events.save(event));
    }

    /**
     * Replaces an event whole, re-resolved against {@code networkId}.
     *
     * <p>PUT rather than PATCH because the timeline holds the bar it is editing: dragging it, or
     * changing its severity in the panel beside it, sends back the event the client already has.
     * That also makes the two checks unconditional — a partial edit would leave the service
     * validating a window half of which came from the request and half from the row.
     */
    public DisruptionEventDto replaceEvent(long eventId, long networkId, long ownerId,
            DisruptionEventRequest request) {
        DisruptionEvent event = requireEvent(eventId, ownerId);
        Network network = requireNetworkOfSameProject(networkId, ownerId, event.getScenario());
        check(request, network);

        event.setTargetType(request.targetType());
        event.setTargetId(request.targetId());
        event.setTargetRegion(normalisedRegion(request));
        apply(request, event);
        return mapper.toDto(event);
    }

    /** Deletes one event. Removed from the aggregate too, so the in-memory scenario stays true. */
    public void deleteEvent(long eventId, long ownerId) {
        DisruptionEvent event = requireEvent(eventId, ownerId);
        event.getScenario().getEvents().remove(event);
        events.delete(event);
    }

    // ----------------------------------------------------------------- the two checks

    /**
     * Everything an event needs a network for, in the order that gives the most useful failure, and
     * before anything is written.
     *
     * <p>Target first: an event pointing at nothing is wrong whatever its timing, and reporting the
     * horizon of a network whose node the event does not even name would send the user to the wrong
     * remedy. Both run ahead of {@link #apply} rather than beside it so a rejected event never
     * half-reaches the row — on a replace that would leave the stored event with a new severity and
     * its old window.
     */
    private void check(DisruptionEventRequest request, Network network) {
        checkWindowPositive(request);
        checkTarget(request, network);
        checkHorizon(request, network);
    }

    /**
     * {@code ck_event_window}: an event has to last a positive amount of time.
     *
     * <p>Not expressible on the DTO. {@link DurationDto} is {@code @PositiveOrZero}, because zero is
     * a legitimate duration nearly everywhere else in the model — a same-period lead time, no dwell
     * at a DC — and tightening it there would reject those. Here it is not: an event that lasts no
     * time has no recovery window, so its recovery profile parameterises nothing.
     *
     * <p>Checked in seconds rather than on the stated value, so {@code {"value": 0.0004, "unit":
     * "SECOND"}} — which is positive as written and rounds to zero seconds in the canonical column —
     * is caught here rather than by the database.
     *
     * @throws IllegalArgumentException rendered as 400, the answer for a malformed request the
     *                                  annotations cannot describe
     */
    private static void checkWindowPositive(DisruptionEventRequest request) {
        DurationAmount duration = amount(request.duration());
        if (duration.getSeconds() <= 0) {
            throw new IllegalArgumentException(
                    ("An event must last a positive amount of time; duration was %s %s. Its "
                            + "duration is what parameterises the recovery profile.")
                            .formatted(request.duration().value(), request.duration().unit()));
        }
    }

    /** The writable part, once {@link #check} has passed. */
    private void apply(DisruptionEventRequest request, DisruptionEvent event) {
        mapper.applyAttributes(request, event);
        mapper.applyTiming(request, event);
    }

    /**
     * The target half: NODE and LINK resolve by id inside this network, REGION by tag.
     *
     * <p>An empty region is refused rather than warned about. The event would be stored, the run
     * would complete, and the results would show a network that shrugged off a disruption it never
     * received — a false negative in the one direction a resilience study cannot afford.
     */
    private void checkTarget(DisruptionEventRequest request, Network network) {
        long networkId = network.getId();
        DisruptionTargetType type = request.targetType();
        Long targetId = request.targetId();
        String region = normalisedRegion(request);

        switch (type) {
            case NODE, LINK -> {
                if (region != null) {
                    throw EventTargetException.wrongHalf(type, networkId);
                }
                if (targetId == null) {
                    throw EventTargetException.missingId(type, networkId);
                }
                boolean resolves = type == DisruptionTargetType.NODE
                        ? nodes.findById(targetId)
                                .filter(node -> node.getNetwork().getId().equals(networkId))
                                .isPresent()
                        : links.existsByIdAndNetworkId(targetId, networkId);
                if (!resolves) {
                    throw EventTargetException.unresolvedId(type, targetId, networkId);
                }
            }
            case REGION -> {
                if (targetId != null) {
                    throw EventTargetException.wrongHalf(type, networkId);
                }
                if (region == null) {
                    throw EventTargetException.missingRegion(networkId);
                }
                if (nodes.countByNetworkIdAndRegion(networkId, region) == 0) {
                    throw EventTargetException.emptyRegion(region, networkId);
                }
            }
        }
    }

    /**
     * {@code EVENT_EXCEEDS_HORIZON}, as a refusal: the event's window must end within the
     * network's horizon.
     *
     * <p>The offset and the window are discretised separately, because that is what the engine will
     * do with them — an offset becomes the index of the step the event fires in, a duration a count
     * of steps it lasts. {@link EventWindow} owns that arithmetic and the editor's warning
     * banner runs the same three lines, so a bar the timeline draws as ending at period 58 is the
     * one this method measures.
     */
    private void checkHorizon(DisruptionEventRequest request, Network network) {
        TimeBasis basis = TimeBasis.of(network.getPeriodLength(), network.getRoundingPolicy());
        EventWindow window = new EventWindow(null, null, amount(request.startOffset()),
                amount(request.duration()));
        if (window.exceeds(basis, network.getHorizonPeriods())) {
            throw new EventHorizonException(network.getId(), window.startPeriod(basis),
                    window.windowPeriods(basis), window.endPeriod(basis),
                    network.getHorizonPeriods());
        }
    }

    // ---------------------------------------------------------------------- helpers

    /**
     * The stated {@code (value, unit)} pair, never restated.
     *
     * <p>Built from the request rather than from the entity so the check runs on what is being
     * written, not on what is still stored — which is the whole point of checking before the mapper
     * has touched the row.
     */
    private static DurationAmount amount(DurationDto dto) {
        return DurationAmount.of(dto.value(), dto.unit());
    }

    /** Blank is not a region: {@code ""} would match no node and read as "unset" to every caller. */
    private static String normalisedRegion(DisruptionEventRequest request) {
        String region = request.targetRegion();
        if (region == null) {
            return null;
        }
        String trimmed = region.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void applySettings(DisruptionScenarioRequest request,
            DisruptionScenario scenario) {
        scenario.setNumReplications(request.numReplications() == null
                ? DisruptionScenario.DEFAULT_REPLICATIONS
                : request.numReplications());
        // A null seed is a value — "draw one per run" — not an omission, so it is written through
        // rather than defaulted.
        scenario.setSeed(request.seed());
    }

    /**
     * "Name (copy)", then "Name (copy 2)", and so on until one is free.
     *
     * <p>Bounded by the number of copies that already exist, so the loop terminates; the alternative
     * — appending a timestamp — produces names nobody would choose to keep.
     */
    private String nextCopyName(long projectId, String baseName) {
        // Truncated inside the loop, not after it: the name whose availability is checked has to be
        // the name that is stored, or a base long enough to overrun the column could pass the check
        // and then collide once it was cut down.
        String candidate = truncate(baseName + " (copy)");
        int suffix = 2;
        while (scenarios.existsByProjectIdAndName(projectId, candidate)) {
            candidate = truncate("%s (copy %d)".formatted(baseName, suffix++));
        }
        return candidate;
    }

    /** {@code disruption_scenario.name} is VARCHAR(160); a derived name must not overrun it. */
    private static String truncate(String name) {
        return name.length() <= NAME_LIMIT ? name : name.substring(0, NAME_LIMIT);
    }

    private void requireNameFree(long projectId, String name) {
        if (scenarios.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateNameException("scenario", "Project " + projectId, name);
        }
    }

    private Project requireProject(long projectId, long ownerId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No project with id " + projectId));
    }

    /**
     * A scenario with its events loaded, or 404.
     *
     * <p>Missing and not-yours are the same answer, following {@code NetworkLookup}: a 403 would
     * confirm that an id exists.
     */
    private DisruptionScenario requireScenario(long scenarioId, long ownerId) {
        return scenarios.findWithEventsById(scenarioId)
                .filter(scenario -> scenario.getProject().getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No scenario with id " + scenarioId));
    }

    private DisruptionEvent requireEvent(long eventId, long ownerId) {
        return events.findById(eventId)
                .filter(event -> event.getScenario().getProject().getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No event with id " + eventId));
    }

    /**
     * The network an event is being authored against.
     *
     * <p>It has to be one of the scenario's own project: a scenario is replayed across the variants
     * of one project, and resolving a target against a network from a different project
     * would validate the event against a structure no run of this scenario will ever use.
     */
    private Network requireNetworkOfSameProject(long networkId, long ownerId,
            DisruptionScenario scenario) {
        Network network = lookup.requireNetwork(networkId, ownerId);
        if (!network.getProject().getId().equals(scenario.getProject().getId())) {
            throw new EntityNotFoundException(
                    "No network with id %d in this scenario's project".formatted(networkId));
        }
        return network;
    }
}
