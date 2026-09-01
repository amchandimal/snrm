package com.snrm.network;

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
 * Networks and configuration variants ({@code GET/POST /projects/{id}/networks},
 * {@code POST /networks/{id}/clone}).
 *
 * <p>Networks are addressed two ways on purpose. Collection operations hang off the project, since
 * that is the only scope in which "the networks" means anything; item operations use
 * {@code /networks/{id}} directly, because a network id is globally unique and the editor holds one
 * long after it has forgotten which project it came from.
 */
@Tag(name = "Networks",
        description = "Configurations of the supply network, and the variants forked from them "
                + "(FR-01, FR-09).")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class NetworkController {

    private final NetworkService networks;
    private final TimeValidationService timeValidation;
    private final CurrentUser currentUser;

    NetworkController(NetworkService networks, TimeValidationService timeValidation,
            CurrentUser currentUser) {
        this.networks = networks;
        this.timeValidation = timeValidation;
        this.currentUser = currentUser;
    }

    @Operation(summary = "List a project's networks",
            description = "Baseline and variants together, ordered by name then version.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The project's networks.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = NetworkDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/projects/{projectId}/networks")
    public List<NetworkDto> listByProject(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId) {
        return networks.listByProject(projectId, currentUser.ownerId());
    }

    @Operation(summary = "Create a network in a project",
            description = """
                    The version number is assigned by the server: the first network of a given name \
                    is version 1 and each later one takes the next, which keeps the variant history \
                    in order.

                    A project may have at most one baseline — the network the comparison view \
                    measures the others against.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the new network.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NetworkDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, blank or too long.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`BASELINE_ALREADY_SET` — the project "
                    + "already has a baseline.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/projects/{projectId}/networks",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NetworkDto> create(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId,
            @Valid @RequestBody NetworkRequest request) {
        NetworkDto created = networks.create(projectId, currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + created.id())).body(created);
    }

    @Operation(summary = "Fetch one network",
            description = "`editable` is false once a simulation run has frozen the network; "
                    + "the editor reads it before the user's first keystroke so it can "
                    + "offer to clone instead of letting an edit fail.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The network.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NetworkDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}")
    public NetworkDto get(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return networks.get(networkId, currentUser.ownerId());
    }

    @Operation(summary = "Rename a network or move the baseline flag onto it",
            description = """
                    **A rename is not a structural edit, and is not refused on a frozen network** \
                    (FR-29). The freeze covers what a result was \
                    computed from — nodes, links, per-product rows, the time base. A name and a \
                    baseline flag are an input to no metric and are read by no calculator, so a \
                    network with completed runs against it renames freely. After a batch import \
                    (FR-28), which names each network after its file, that is the common case \
                    rather than the exception.

                    **Name and baseline flag travel together**, because this endpoint replaces \
                    both: a rename that omits `baseline` clears the flag. The project dashboard \
                    edits the two as one thing for exactly that reason.

                    A project has at most one baseline and this endpoint never moves the flag off \
                    another network — designating a new baseline is two requests, clearing the \
                    incumbent first.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated network.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NetworkDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, blank or too long.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`BASELINE_ALREADY_SET` — another "
                    + "network of the project holds the flag. Never `NETWORK_IMMUTABLE`: this is "
                    + "the one write to a network that a freeze does not cover (FR-29).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/networks/{networkId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NetworkDto update(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Valid @RequestBody NetworkRequest request) {
        return networks.update(networkId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Set a network's period length, horizon and rounding policy",
            description = """
                    The network's clock. Every duration in the network — lead times, \
                    processing times, event windows — is discretised against it when the \
                    `NetworkGraph` snapshot is built, so this is the single setting that decides \
                    what "one period" means everywhere else.

                    The response carries the resolution findings the new clock produces \
                    alongside the updated network, because changing the period is exactly when a \
                    6-hour lead time stops being representable — the editor fills its warning \
                    banner from the response that caused it.

                    Refused with `NETWORK_IMMUTABLE` once a simulation run has frozen the network. \
                    Results are stated in periods, so redefining the period would turn "TTR = 14" \
                    from fourteen days into fourteen hours without changing a stored number — the \
                    results are not comparable across time bases, and nothing about them would look \
                    different. Clone the network and set the time base on the \
                    variant.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated network and its findings.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TimeBaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "A field is missing, the period is not "
                    + "positive, or the horizon is below 1.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE` — a simulation "
                    + "run has frozen this network; results are not comparable across time bases. "
                    + "Fork a variant.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/api/v1/networks/{networkId}/time-base",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public TimeBaseResponse setTimeBase(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Valid @RequestBody TimeBaseRequest request) {
        return networks.setTimeBase(networkId, currentUser.ownerId(), request);
    }

    @Operation(summary = "Check what this network's period does to its declared durations",
            description = """
                    The resolution validation: every duration that converts to zero \
                    periods, rounds by more than 10%, or needs more than a thousand steps to span, \
                    plus the coarsest period that would keep all of them within 10% — the \
                    "suggest period" action of the settings dialog.

                    Nothing is refused here; the findings are data. Severity follows the context: \
                    a duration that converts to zero periods is a warning while editing and an \
                    error during import, which is what `context` selects.

                    Name a `scenarioId` from the same project to have its events checked against \
                    this network's horizon as well. Without one, no `EVENT_EXCEEDS_HORIZON` \
                    finding can appear: scenarios are project-scoped so they can be replayed \
                    against every variant, so whether an event outruns the horizon is a \
                    question about the pair, not about either alone.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The findings, worst first.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TimeValidationReport.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user, or no "
                    + "such scenario in its project.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/time-validation")
    public TimeValidationReport timeValidation(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Parameter(description = "Scenario of the same project whose events should be checked "
                    + "against this network's horizon.", example = "3")
            @RequestParam(required = false) Long scenarioId,
            @Parameter(description = "Which severity rule to apply.",
                    example = "EDITOR")
            @RequestParam(required = false, defaultValue = "EDITOR") TimeValidationContext context) {
        return timeValidation.validate(networkId, scenarioId, currentUser.ownerId(), context);
    }

    @Operation(summary = "Delete a network and everything in it",
            description = "Nodes, links and their per-product rows cascade. Refused with "
                    + "`NETWORK_IMMUTABLE` if a simulation run points at the network: deleting it "
                    + "would destroy the inputs of a persisted result.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`NETWORK_IMMUTABLE` — frozen by a "
                    + "simulation run.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/networks/{networkId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        networks.delete(networkId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Clone a network as a configuration variant",
            description = """
                    Copies the whole structure — nodes, links and per-node product parameters — into \
                    a new network and records the provenance as a `CONFIGURATION_VARIANT` \
                    (FR-09).

                    Products are not copied: they are project-scoped, so the copy draws on the same \
                    catalogue.

                    This is the remedy `NETWORK_IMMUTABLE` points at, so it works on a frozen \
                    network — that is the normal reason to call it. The copy takes the next version \
                    number under its name and is never the baseline.

                    `leverChanges` is the structured diff from the base network. Recording it is \
                    what makes lever-level attribution of metric improvements possible later.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cloned; the variant carries the new network.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ConfigurationVariantDto.class))),
            @ApiResponse(responseCode = "400", description = "Name too long, or `leverChanges` is "
                    + "not valid JSON.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/networks/{networkId}/clone",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfigurationVariantDto> clone(
            @Parameter(description = "Id of the network to fork from.", example = "1")
            @PathVariable long networkId,
            @Valid @RequestBody CloneNetworkRequest request) {
        ConfigurationVariantDto variant = networks.clone(networkId, currentUser.ownerId(), request);
        return ResponseEntity
                .created(URI.create("/api/v1/networks/" + variant.network().id()))
                .body(variant);
    }

    @Operation(summary = "List the variants forked from a network",
            description = "The provenance tree of a baseline, with each variant's lever diff.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The variants forked from this network.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ConfigurationVariantDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/variants")
    public List<ConfigurationVariantDto> variants(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return networks.variantsOf(networkId, currentUser.ownerId());
    }
}
