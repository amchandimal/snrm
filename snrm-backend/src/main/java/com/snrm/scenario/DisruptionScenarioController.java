package com.snrm.scenario;

import com.snrm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Disruption scenario definition — {@code CRUD /projects/{id}/scenarios} and
 * {@code /scenarios/{id}/events} (FR-05).
 *
 * <p>The two halves sit at different levels because they belong to different things. A scenario is
 * project-scoped, so one disruption story can be replayed against every configuration variant, which
 * is what makes the comparison view meaningful. An event is a child of the scenario
 * but is <strong>authored against a network</strong>: its target is a node id, a link id or a region
 * tag, and its window has to fit inside a horizon. Hence the {@code networkId} query parameter on
 * every event write — required, unstored, and explained on {@link DisruptionScenarioService}.
 *
 * <p><strong>Two rejections a client should expect to branch on.</strong>
 * {@code EVENT_TARGET_INVALID} (422) means the event names something the network does not have;
 * {@code EVENT_EXCEEDS_HORIZON} (422) means its window runs past the end of the run. The second
 * shares its code with the finding, deliberately, so a client that can already explain
 * the editor's warning banner needs no second vocabulary for the same rule.
 */
@Tag(name = "Scenarios",
        description = "Disruption scenarios and the events in them — what is struck, when, how hard, "
                + "and how it recovers (FR-05).")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class DisruptionScenarioController {

    /** Shared by the two event writes, which differ only in verb. */
    private static final String EVENT_WRITE_DESCRIPTION = """
            `networkId` names the network the event is authored against. It is required and it is \
            not stored: a scenario stays project-scoped so it can be replayed against every variant, \
            but nothing about an event can be checked without a network — its target is a \
            node id, a link id or a region tag, and its window is measured against a period length \
            and a horizon.

            Two checks follow from that, in this order. The **target** must resolve in that network: \
            NODE and LINK by id, REGION by a `node.region` tag at least one node carries. A region \
            matching nothing is refused rather than warned about — the run would complete and the \
            results would show a network shrugging off a disruption it never received. The \
            **window** must end inside the horizon: `startOffset` and `duration` are discretised \
            separately, as the engine discretises them, and their sum must not exceed \
            `horizonPeriods` — row 4, refused here rather than reported, because an \
            event whose recovery is never observed makes every metric over it a measurement of the \
            truncation.

            Both durations are unit-bearing and required: `{"value": 4, "unit": "WEEK"}`, measured \
            from the start of the horizon, never a count of periods.""";

    private final DisruptionScenarioService scenarios;
    private final CurrentUser currentUser;

    DisruptionScenarioController(DisruptionScenarioService scenarios, CurrentUser currentUser) {
        this.scenarios = scenarios;
        this.currentUser = currentUser;
    }

    // --------------------------------------------------------------------- scenarios

    @Operation(summary = "List a project's disruption scenarios",
            description = "Ordered by name. Each row carries `eventCount`; the events themselves "
                    + "come with the single-scenario read, since the sidebar shows a row "
                    + "per scenario rather than every bar of every timeline.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The project's scenarios.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = DisruptionScenarioDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/projects/{projectId}/scenarios")
    public List<DisruptionScenarioDto> list(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId) {
        return scenarios.listByProject(projectId, currentUser.ownerId());
    }

    @Operation(summary = "Create a disruption scenario",
            description = "Project-scoped, so it can be replayed against every configuration variant "
                    + "of the project and their results stay comparable. Events are added "
                    + "afterwards, one at a time, because each is validated against a network.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the scenario.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DisruptionScenarioDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, or `numReplications` below 1.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` — the project has a "
                    + "scenario with that name.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/projects/{projectId}/scenarios",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DisruptionScenarioDto> create(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId,
            @Valid @RequestBody DisruptionScenarioRequest request) {
        DisruptionScenarioDto created = scenarios.create(projectId, currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/scenarios/" + created.id())).body(created);
    }

    @Operation(summary = "Fetch one scenario with its events",
            description = "Events are ordered by start offset — by the derived second count, which "
                    + "is the only ordering that is correct once two events in one scenario are "
                    + "written in different units. This is what the timeline "
                    + "is drawn from.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The scenario and its events.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DisruptionScenarioDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such scenario for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/scenarios/{scenarioId}")
    public DisruptionScenarioDto get(
            @Parameter(description = "Scenario id.", example = "1") @PathVariable long scenarioId) {
        return scenarios.get(scenarioId, currentUser.ownerId());
    }

    @Operation(summary = "Rename a scenario and replace its Monte Carlo settings",
            description = "Not blocked by network immutability: a scenario is part of no "
                    + "network, and a completed run records the parameters it actually used, so "
                    + "editing the scenario afterwards cannot rewrite a historical result. "
                    + "Events are unaffected.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated scenario.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DisruptionScenarioDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, or `numReplications` below 1.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such scenario for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/scenarios/{scenarioId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DisruptionScenarioDto replace(
            @Parameter(description = "Scenario id.", example = "1") @PathVariable long scenarioId,
            @Valid @RequestBody DisruptionScenarioRequest request) {
        return scenarios.replace(scenarioId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Duplicate a scenario, events included",
            description = """
                    A deep copy: every event comes with it, timings and units verbatim. This is how \
                    a variation on a disruption story is explored — the same fire, two weeks later \
                    — without editing the scenario a completed run already refers to.

                    The body is optional, and so is the `Content-Type` header with it: this is the \
                    one write in the API that declares no `consumes`, so a bare POST works. Omit \
                    the body, or omit `name`, and the copy is called "<name> (copy)", with a \
                    numeric suffix if that is taken too.

                    No target is re-validated: the copy holds the same ids and tags as its source \
                    and is authored against no new network, so there is nothing to resolve.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the copy.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DisruptionScenarioDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such scenario for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` — the name given is "
                    + "already in use in the project.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/api/v1/scenarios/{scenarioId}/duplicate")
    public ResponseEntity<DisruptionScenarioDto> duplicate(
            @Parameter(description = "Scenario id.", example = "1") @PathVariable long scenarioId,
            @Valid @RequestBody(required = false) DuplicateScenarioRequest request) {
        DisruptionScenarioDto copy = scenarios.duplicate(scenarioId, currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/scenarios/" + copy.id())).body(copy);
    }

    @Operation(summary = "Delete a scenario",
            description = "Its events go with it. A scenario referenced by a completed simulation "
                    + "run cannot be deleted while that run exists — the run's results are only "
                    + "interpretable beside the disruptions that produced them.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such scenario for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/scenarios/{scenarioId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Scenario id.", example = "1") @PathVariable long scenarioId) {
        scenarios.delete(scenarioId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------ events

    @Operation(summary = "List a scenario's events",
            description = "In timeline order, over `ix_event_window`. Same rows as the `events` of "
                    + "`GET /api/v1/scenarios/{scenarioId}`, for a client that already has the "
                    + "scenario and only wants its bars back.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The scenario's events.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = DisruptionEventDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such scenario for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/scenarios/{scenarioId}/events")
    public List<DisruptionEventDto> listEvents(
            @Parameter(description = "Scenario id.", example = "1") @PathVariable long scenarioId) {
        return scenarios.listEvents(scenarioId, currentUser.ownerId());
    }

    @Operation(summary = "Add an event to a scenario", description = EVENT_WRITE_DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the event.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DisruptionEventDto.class))),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — severity or "
                    + "probability outside [0,1], a missing or unit-less duration, an unknown "
                    + "`recoveryProfile` or `targetType`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such scenario for this user, or no "
                    + "such network in the scenario's project.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "`EVENT_TARGET_INVALID` — the target "
                    + "does not resolve in that network — or `EVENT_EXCEEDS_HORIZON`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/scenarios/{scenarioId}/events",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DisruptionEventDto> createEvent(
            @Parameter(description = "Scenario id.", example = "1") @PathVariable long scenarioId,
            @Parameter(description = "The network the event is authored against. Its targets are "
                    + "resolved in this network and its window measured against this network's "
                    + "clock and horizon. Must belong to the scenario's project.", example = "1",
                    required = true)
            @RequestParam long networkId,
            @Valid @RequestBody DisruptionEventRequest request) {
        DisruptionEventDto created =
                scenarios.createEvent(scenarioId, networkId, currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/events/" + created.id())).body(created);
    }

    @Operation(summary = "Fetch one event")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The event.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DisruptionEventDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such event for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/events/{eventId}")
    public DisruptionEventDto getEvent(
            @Parameter(description = "Event id.", example = "1") @PathVariable long eventId) {
        return scenarios.getEvent(eventId, currentUser.ownerId());
    }

    @Operation(summary = "Replace an event",
            description = "A full replacement, not a patch: the timeline holds the bar it is "
                    + "editing, so dragging it or changing its severity sends back the whole event. "
                    + "That also keeps the two checks unconditional — a partial edit would have the "
                    + "server validating a window half of which came from the request and half from "
                    + "the row.\n\n" + EVENT_WRITE_DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated event.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DisruptionEventDto.class))),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — see the create.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such event for this user, or no "
                    + "such network in the scenario's project.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "`EVENT_TARGET_INVALID` or "
                    + "`EVENT_EXCEEDS_HORIZON`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/events/{eventId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DisruptionEventDto replaceEvent(
            @Parameter(description = "Event id.", example = "1") @PathVariable long eventId,
            @Parameter(description = "The network the event is authored against.", example = "1",
                    required = true)
            @RequestParam long networkId,
            @Valid @RequestBody DisruptionEventRequest request) {
        return scenarios.replaceEvent(eventId, networkId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Delete an event",
            description = "No `networkId`: removing a bar needs nothing resolved.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such event for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/events/{eventId}")
    public ResponseEntity<Void> deleteEvent(
            @Parameter(description = "Event id.", example = "1") @PathVariable long eventId) {
        scenarios.deleteEvent(eventId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }
}
