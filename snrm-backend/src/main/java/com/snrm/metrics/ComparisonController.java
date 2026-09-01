package com.snrm.metrics;

import com.snrm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The comparison view's one endpoint
 * ({@code GET /projects/{id}/comparison?networkIds=…}).
 *
 * <p>Project-scoped rather than network-scoped because a comparison is about several networks at
 * once and no one of them owns it — and because the project is what bounds which networks may
 * legitimately appear in the same table (a scenario, a product catalogue and a baseline are
 * all project-scoped).
 *
 * <p>The export of the same matrix is {@code GET /projects/{id}/comparison/export}, next door in
 * {@link ComparisonExportController}, because it answers with a file rather than JSON.
 */
@Tag(name = "Comparison",
        description = "The variants × metrics matrix behind the comparison view "
                + "(FR-10).")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ComparisonController {

    private final ComparisonService comparisons;
    private final CurrentUser currentUser;

    ComparisonController(ComparisonService comparisons, CurrentUser currentUser) {
        this.comparisons = comparisons;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Compare configuration variants across the metric suite",
            description = """
                    The matrix: one column per configuration, one row per \
                    network-scoped metric, with the winner of each row already decided and every \
                    column carrying the lever diff that explains it.

                    **Which run each column reads from.** The most recent `DONE` run of that \
                    network — narrowed to one scenario if `scenarioId` is given, which is the form \
                    worth asking for: comparing one variant under a plant outage against another \
                    under a port closure measures the scenarios, and the lever change takes the \
                    credit. If the request does not pin a scenario and the chosen runs disagree \
                    about it, the response says so through the `MIXED_SCENARIOS` note rather than \
                    refusing.

                    **A variant with no run keeps its column.** It fills the topological half of \
                    the suite — computed on save, so a fork can be judged structurally before an \
                    hour of Monte Carlo is spent on it — and the simulated cells come \
                    back `null`, with a `NO_RUN` note. Null is unmeasured, never zero.

                    **Time-valued metrics arrive in one unit**. `TTR` is stored as a \
                    count of *that network's* periods, so two columns clocked differently hold \
                    numbers on different scales. Every such value is converted to the finest period \
                    unit among the compared variants, and each cell keeps its original period count \
                    beside the converted value. A comparison spanning different period lengths is \
                    flagged with `mixedTimeBases` and a `MIXED_TIME_BASES` note — the numbers are \
                    comparable after conversion, but the models are not identical.

                    **Rows are network-scoped only.** `NODE_CRITICALITY` is one value per node \
                    and belongs to the results dashboard's per-node table, not to a \
                    matrix whose rows are metrics.

                    **`runIds` keys the columns by run instead** (FR-17). One column per named \
                    run, in request order — which is what expresses the two questions the \
                    network-keyed form cannot: a network's baseline run beside its disruption run, \
                    and one network under two different scenarios. Mutually exclusive with \
                    `networkIds` and `scenarioId`: the two selectors answer the same question two \
                    ways, and a request carrying both would be ambiguous about which one it asked. \
                    Everything else — the DTO, the unit conversion, the best-in-row — is identical, \
                    and each column labels itself through `runId`, `scenarioId` and `scenarioName` \
                    (null scenario = a baseline run). A named run that is not yet DONE is a 409 \
                    `RUN_NOT_DONE`.

                    Omit `networkIds` to compare every network in the project.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The matrix.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ComparisonMatrixDto.class))),
            @ApiResponse(responseCode = "400",
                    description = "Both runIds and networkIds/scenarioId were given.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such project for this user, or a named network or run is "
                            + "not in it.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "A named run is not DONE yet (RUN_NOT_DONE).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/projects/{projectId}/comparison")
    public ComparisonMatrixDto compare(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId,

            @Parameter(description = "The configurations to compare, in the order they should "
                    + "appear as columns. Repeat the parameter or comma-separate. Omit to compare "
                    + "every network in the project.", example = "1,4,5")
            @RequestParam(value = "networkIds", required = false) List<Long> networkIds,

            @Parameter(description = "Pin every column to this disruption scenario's most recent "
                    + "completed run. Omit to take each network's most recent completed run "
                    + "whatever scenario it applied — which is reported as MIXED_SCENARIOS if they "
                    + "differ.", example = "1")
            @RequestParam(value = "scenarioId", required = false) Long scenarioId,

            @Parameter(description = "Compare these runs, one column each, in this order (FR-17). "
                    + "Mutually exclusive with networkIds and scenarioId.", example = "12,13")
            @RequestParam(value = "runIds", required = false) List<Long> runIds) {

        if (runIds != null && !runIds.isEmpty()) {
            requireRunSelectorAlone(networkIds, scenarioId);
            return comparisons.compareRuns(projectId, currentUser.ownerId(), runIds);
        }
        return comparisons.compare(projectId, currentUser.ownerId(), networkIds, scenarioId);
    }

    /**
     * The two selectors are mutually exclusive (FR-17). Package-private and static so the export
     * controller enforces the identical rule rather than a paraphrase of it.
     */
    static void requireRunSelectorAlone(List<Long> networkIds, Long scenarioId) {
        if ((networkIds != null && !networkIds.isEmpty()) || scenarioId != null) {
            throw new IllegalArgumentException("runIds is mutually exclusive with networkIds and "
                    + "scenarioId: columns are keyed either by network or by run, and a request "
                    + "carrying both is ambiguous about which it asked for.");
        }
    }
}
