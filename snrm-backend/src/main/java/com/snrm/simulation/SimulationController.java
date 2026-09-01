package com.snrm.simulation;

import com.snrm.auth.CurrentUser;
import com.snrm.metrics.MetricResultDto;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Simulation submission and results.
 *
 * <blockquote><pre>
 * POST /api/v1/simulations                             -&gt; 202 Accepted {jobId}
 * GET  /api/v1/simulations/{runId}                     -&gt; full results
 * GET  /api/v1/simulations/{runId}/results             -&gt; metric suite with CIs
 * GET  /api/v1/simulations/{runId}/timeseries          -&gt; per-period curves
 * GET  /api/v1/simulations/{runId}/timeseries/elements -&gt; per-element curves
 * GET  /api/v1/simulations/{runId}/timeseries/nodes/{nodeId}    -&gt; one node
 * GET  /api/v1/simulations/{runId}/timeseries/links/{linkId}    -&gt; one link
 * GET    /api/v1/networks/{networkId}/runs -&gt; this network's runs (FR-20)
 * DELETE /api/v1/simulations/{runId}       -&gt; discard one run     (FR-20)
 * DELETE /api/v1/networks/{networkId}/runs -&gt; discard all of them (FR-20)
 * </pre></blockquote>
 *
 * <p>The three network-keyed paths live here rather than on {@code NetworkController} because what
 * they address is a <em>run</em> — its lifecycle, its results, and what deleting it does to the
 * freeze. {@code NetworkController} owns the network aggregate and would otherwise grow a
 * dependency on the simulation module to answer a question about simulation rows.
 *
 * <p>The narrow reads are not redundant with the wide one. Opening a run returns full results
 * (metrics + time series); the two halves are served separately as well, because a
 * comparison view listing eight variants wants a dozen metric rows each and not eight horizons of
 * per-period points, while a curve redrawn on a zoom wants the opposite.
 *
 * <p>The three element reads are deliberately outside {@code SimulationResultsDto} for
 * the same reason taken one step further: a per-element series is {@code horizon × (nodes + links)}
 * numbers, and making the results view pay for half a million of them to draw a metric table would
 * invert the split the section above exists to make. Every one of them checks ownership through the
 * same {@code requireRun} the other reads use, and answers {@code available: false} — never a 404,
 * never a 500 — for a run that has no such series.
 *
 * <p>Polling and cancellation are not here — they are {@code /jobs/{jobId}}, and live in
 * {@code common} because the Phase 2 configuration search will use the same two endpoints.
 */
@Tag(name = "Simulations",
        description = "Monte Carlo evaluation of a network against a disruption scenario. "
                + "Submission is asynchronous: poll the returned jobId at GET /jobs/{id}.")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class SimulationController {

    private final SimulationService simulations;
    private final CurrentUser currentUser;

    SimulationController(SimulationService simulations, CurrentUser currentUser) {
        this.simulations = simulations;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Submit a simulation job",
            description = """
                    Validates the request, records the run, and queues the job. Answers \
                    202 with a `jobId` to poll at `GET /jobs/{jobId}` and the `runId` whose results \
                    will appear at `GET /simulations/{runId}`.

                    **Everything is checked before the 202.** The network and the scenario must be \
                    yours and in the same project; every event's window must fit this network's \
                    horizon; every event must resolve to a node, link or region *this* network has; \
                    and the network must have demand to serve. A submission that fails any of these \
                    is a 4xx now rather than a `FAILED` poll in ten seconds.

                    **The run freezes the network.** A `QUEUED` run is network-locking, \
                    so from the moment this returns, editing the network is refused with \
                    `NETWORK_IMMUTABLE` and must fork a variant. Cancelling the job releases it \
                    again — `CANCELLED` locks nothing.

                    **What actually runs.** Twice the disrupted replication count, because every \
                    simulation includes the undisrupted baseline set the recovery metrics are \
                    defined against. The baseline replications are paired with the \
                    disrupted ones and share their demand realisations, so `LOSS_AREA` and \
                    `DISRUPTION_COST_DELTA` are paired differences.

                    **No scenario is the baseline run** (FR-17): the network evaluated with \
                    nothing going wrong — the comparator every disrupted run is read against, and \
                    the first thing worth running on a network just built or imported. It executes \
                    exactly N replications, not 2N: the pairing exists to isolate a disruption's \
                    effect and there is none to isolate. Its suite omits TTR, LOSS_AREA, \
                    DISRUPTION_COST_DELTA and RESILIENCE_INDEX — absent rows, never zeros. \
                    Replications and seed come from `params`, falling back to the engine defaults.

                    **While it runs**, the job poll carries provisional figures — see \
                    `GET /jobs/{jobId}` and the `partial` object (FR-17).

                    **Reproducibility.** The response carries the fully resolved parameter set, \
                    including the seed actually drawn — the same object stored in \
                    `simulation_run.params_json`. Re-submitting it reproduces the run exactly.\
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Validated and queued.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SimulationAcceptedDto.class))),
            @ApiResponse(responseCode = "400", description = "Malformed request body.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such network or scenario for this user, or the two are in "
                            + "different projects.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422",
                    description = "`EVENT_EXCEEDS_HORIZON`, `EVENT_TARGET_UNRESOLVED` or "
                            + "`NETWORK_HAS_NO_DEMAND`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429",
                    description = "`JOB_QUEUE_FULL` — the bounded job queue is full. "
                            + "Retry once a running job finishes.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/api/v1/simulations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SimulationAcceptedDto> submit(
            @Valid @RequestBody SimulationRequest request) {
        SimulationAcceptedDto accepted = simulations.submit(request, currentUser.ownerId());
        // Location points at the job, not the run: the job is what the caller has to do something
        // with next, and 202 means "here is where to watch this", not "here is the result".
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/jobs/" + accepted.jobId()))
                .body(accepted);
    }

    @Operation(summary = "A run with its metric suite and its curves",
            description = """
                    The whole of one run. Available immediately after submission: a \
                    `QUEUED` or `RUNNING` run answers with its record and two empty lists rather \
                    than a 404, so the results view can open on a run that is still executing and \
                    poll the job beside it.

                    `run.params` is the parameter set that actually ran, read back from \
                    `params_json` — the seed included.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The run, its metrics and its curves.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SimulationResultsDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/simulations/{runId}")
    public SimulationResultsDto results(
            @Parameter(description = "Run id, as returned at submission.", example = "12")
            @PathVariable long runId) {
        return simulations.results(runId, currentUser.ownerId());
    }

    @Operation(summary = "A run's metric suite",
            description = """
                    The eleven simulated metrics — `FILL_RATE`, `SERVICE_LEVEL`, `TTR`, \
                    `MIN_FILL_RATE`, `LOSS_AREA`, `CVAR_COST`, `TOTAL_COST`, \
                    `DISRUPTION_COST_DELTA`, `RESILIENCE_INDEX`, `AVG_INVENTORY` and \
                    `AVG_PIPELINE` — each a mean across replications with a 95% confidence \
                    interval.

                    **Some rows may be absent, and that is meaningful.** A metric that is undefined \
                    for this run produces no row rather than a zero: `TTR` has nothing to report if \
                    no replication was disrupted, and `SERVICE_LEVEL` has nothing to report if no \
                    period had demand.

                    **`CVAR_COST` carries no interval.** It is a functional of the whole \
                    replication set rather than a mean of per-replication values, so a `ciLow` and \
                    `ciHigh` computed the way the others are would overstate its precision.

                    **`TTR` carries a `displayUnit`.** It is the one time-valued metric in the \
                    suite, so it reads as "3 periods (3 DAY)" rather than a bare 3.

                    Empty while the run is still queued or executing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The suite, with confidence intervals.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = MetricResultDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/simulations/{runId}/results")
    public List<MetricResultDto> metrics(
            @Parameter(description = "Run id.", example = "12") @PathVariable long runId) {
        return simulations.metricResults(runId, currentUser.ownerId());
    }

    @Operation(summary = "A run's per-period curves",
            description = """
                    One point per period, averaged across replications. Carries \
                    **both** curves: the disrupted run and the undisrupted baseline set. \
                    The area between their fill rates is `LOSS_AREA`, so the resilience \
                    triangle is drawable from this response alone, without recomputation.

                    There is no baseline `totalDemand` because it would always equal the disrupted \
                    run's: the paired replications share their demand realisations. Baseline fill \
                    rate is `baselineServedDemand / totalDemand`.

                    Empty while the run is still queued or executing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The curves, in period order.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = RunTimeseriesDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/simulations/{runId}/timeseries")
    public List<RunTimeseriesDto> timeseries(
            @Parameter(description = "Run id.", example = "12") @PathVariable long runId) {
        return simulations.timeseries(runId, currentUser.ownerId());
    }

    @Operation(summary = "A run's per-element curves",
            description = """
                    What every node and every link did in every period, averaged across \
                    replications (FR-18). This is the data the playback view animates: \
                    inventory bars at the nodes, flow ribbons on the links, and the disruption \
                    overlay on the elements an event actually touched — none of which \
                    `GET /timeseries` can be disaggregated back into.

                    **Parallel arrays, indexed by period.** Each element carries one array per \
                    quantity, all of length = the run's horizon, so index `t` is period `t`. A \
                    view binds an array to a visual channel and re-binds it when the scrubber \
                    moves; a list of per-period objects would repeat the index eleven times a \
                    period and make every channel a client-side projection.

                    **Two arrays carry nulls, and a null is a claim.** `inboundLead` is the \
                    dispatch-weighted transport lead of everything sent toward a node that \
                    period — null where nothing was, because 0 would say material arrived \
                    instantly. `utilisation` is null where the arc declares no capacity (an \
                    unconstrained element cannot be partly disrupted) and where an outage left it \
                    none (0/0 is not 0, and a dark link is not an idle one). A link that simply \
                    carried nothing at full availability is a true 0.0.

                    **`flow` is what was DISPATCHED.** On an arc with a lead time it lands at the \
                    target in a later period and shows up in that node's `arrivals` — the offset \
                    a playback view is most likely to render wrongly.

                    **`available: false` is not an error.** It comes back — with empty lists, \
                    never a 404 and never a 500 — while the run is still queued or executing, for \
                    a run that predates this feature, and for one submitted with \
                    `params.recordElementTimeseries: false`. An element the run recorded nothing \
                    for is the other case: `available: true` with an empty list.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The per-element series, or `available: false`.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ElementTimeseriesDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/simulations/{runId}/timeseries/elements")
    public ElementTimeseriesDto elementTimeseries(
            @Parameter(description = "Run id.", example = "12") @PathVariable long runId) {
        return simulations.elementTimeseries(runId, currentUser.ownerId());
    }

    @Operation(summary = "One node's per-element curves",
            description = """
                    The same envelope as `/timeseries/elements`, narrowed to one node — \
                    what a drill-down chart on a single facility needs without pulling the whole \
                    network's horizon.

                    The response shape is deliberately unchanged: `nodes` holds the one entry, \
                    `links` is empty, and `available` keeps its single meaning — *this run has a \
                    per-element series at all*. A node id this run recorded nothing for answers \
                    `available: true` with an empty list, which is a different statement from a \
                    run that recorded nothing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The node's series, or empty.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ElementTimeseriesDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/simulations/{runId}/timeseries/nodes/{nodeId}")
    public ElementTimeseriesDto nodeTimeseries(
            @Parameter(description = "Run id.", example = "12") @PathVariable long runId,
            @Parameter(description = "Node id, of the run's own network.", example = "12")
            @PathVariable long nodeId) {
        return simulations.nodeTimeseries(runId, nodeId, currentUser.ownerId());
    }

    @Operation(summary = "One link's per-element curves",
            description = """
                    The same envelope as `/timeseries/elements`, narrowed to one link. \
                    `links` holds the one entry and `nodes` is empty.

                    Remember what `flow` is: material **dispatched** onto the arc in that period. \
                    On an arc with a lead time it arrives at the target later, so this series and \
                    the target node's `arrivals` are offset by the lead — which is the mechanic \
                    `samples/four-echelon-playback/README.md` §8.3 works through.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The link's series, or empty.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ElementTimeseriesDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/simulations/{runId}/timeseries/links/{linkId}")
    public ElementTimeseriesDto linkTimeseries(
            @Parameter(description = "Run id.", example = "12") @PathVariable long runId,
            @Parameter(description = "Link id, of the run's own network.", example = "3")
            @PathVariable long linkId) {
        return simulations.linkTimeseries(runId, linkId, currentUser.ownerId());
    }

    // ------------------------------------------------------------------ FR-20: discarding a run

    @Operation(summary = "This network's simulation runs",
            description = """
                    Every run recorded against one network, newest first, as records without their \
                    results (FR-20).

                    **This is what a network being frozen actually consists of.** A network's \
                    immutability is derived from these rows rather than stored on the network, so \
                    this list is the whole and only reason `network.editable` is false — and \
                    deleting the last run in a locking state (`QUEUED`, `RUNNING`, `DONE`) makes \
                    the network editable again with nothing else to reset.

                    Read it before offering to discard: the confirmation has to state \
                    how many results will be destroyed, and warn separately when one of them is a \
                    **restored archive result** — `importedAt` non-null, meaning these numbers were \
                    computed by another installation and this may be the only copy here.

                    Records only. The metric suite and the curves stay behind \
                    `GET /simulations/{runId}`, so listing runs never pays for their curves.

                    An empty list is a perfectly ordinary answer: a network nobody has run yet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The runs, newest first.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = SimulationRunDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/runs")
    public List<SimulationRunDto> networkRuns(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return simulations.networkRuns(networkId, currentUser.ownerId());
    }

    @Operation(summary = "Delete a run and everything it produced",
            description = """
                    Discards one simulation run: its metric rows and all three time-series tables \
                    go with it, by declared `ON DELETE CASCADE` (FR-20). Answers **204**.

                    **This is the documented exit from the freeze.** A run that exists \
                    keeps its network frozen; a run that is deleted takes its claim with it, and \
                    when the last locking run of a network is gone the network is editable again \
                    with nothing to reset — the freeze was never stored, only derived from these \
                    rows. Fork-to-variant keeps a result and keeps editing; deletion says the \
                    result was not worth keeping. A run that is *kept* is exactly as immutable and \
                    network-freezing as before: what is deliberately not offered is a run that \
                    never freezes.

                    **Refused with `RUN_ACTIVE` (409) while the run is `QUEUED` or `RUNNING`.** A \
                    worker either holds the run or is about to, and cancellation is cooperative \
                    — so cancel with `DELETE /jobs/{jobId}` first and delete once the run \
                    reports `CANCELLED`.

                    **A restored run (`importedAt`) is deletable like any other**, and the \
                    caller is expected to have said so: these results were computed by another \
                    installation and this may be the only copy here, which is a sentence for the \
                    confirmation dialog rather than a reason to refuse.

                    Topological metric rows are untouched — they carry `run_id = NULL` and belong \
                    to the network, not to any run.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "`RUN_ACTIVE` — the run is QUEUED or RUNNING; cancel its job "
                            + "first.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/simulations/{runId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Run id.", example = "12") @PathVariable long runId) {
        simulations.delete(runId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Discard every run of a network",
            description = """
                    The same as `DELETE /simulations/{runId}`, for every run of one network in one \
                    request — the editor's *"discard this network's runs and edit in place"* \
                    (FR-20). Answers **204**.

                    One request rather than N because the question is about the *network*: stop \
                    this being frozen. A client looping over runs would release the freeze somewhere \
                    inside its own loop and have nothing to say if the third call failed.

                    **Active runs are not silently skipped.** The request is refused whole with \
                    `RUN_ACTIVE` (409) while any run is `QUEUED` or `RUNNING`, and nothing is \
                    deleted. A half-discard is worse than either whole answer: it would destroy \
                    every completed result and leave the network frozen anyway, since an active run \
                    locks it too — the one thing the caller asked for would be the one \
                    thing they did not get. Cancel the job(s) and repeat.

                    A network with no runs answers 204. The caller asked for a network with no \
                    runs, and that is what they have.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204",
                    description = "Every run of the network is gone; the network is editable again "
                            + "unless a new run is created.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "`RUN_ACTIVE` — at least one run is QUEUED or RUNNING. Nothing "
                            + "was deleted.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/networks/{networkId}/runs")
    public ResponseEntity<Void> deleteNetworkRuns(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        simulations.deleteNetworkRuns(networkId, currentUser.ownerId());
        return ResponseEntity.noContent().build();
    }
}
