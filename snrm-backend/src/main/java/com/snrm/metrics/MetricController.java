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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The metric suite over the network's structure
 * ({@code GET /networks/{id}/metrics/topological}).
 *
 * <p>Simulated metrics are not here. They belong to a run and arrive through
 * {@code GET /simulations/{runId}/results} — the split follows {@code MetricKind}, and
 * the two halves of the suite reach a client by different routes because they have different
 * lifetimes: one is recomputed as the network is edited, the other is frozen with the run that
 * produced it.
 */
@Tag(name = "Metrics",
        description = "The resilience metric suite. This controller carries its "
                + "topological half — computed from the graph, on demand, with no simulation.")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class MetricController {

    private final TopologicalMetricsService metrics;
    private final CurrentUser currentUser;

    MetricController(TopologicalMetricsService metrics, CurrentUser currentUser) {
        this.metrics = metrics;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Compute the topological metric suite for a network",
            description = """
                    The structural half of the suite — `DENSITY`, `SPOF_NODE_COUNT`, \
                    `SPOF_ARC_COUNT`, `SPOF_COUNT`, `AVG_PATH`, `CLUSTERING`, \
                    `NODE_CRITICALITY` (one row per node), `ROBUSTNESS_RANDOM` and \
                    `ROBUSTNESS_TARGETED` — computed from an immutable snapshot of the network and \
                    returned in one call.

                    Rows arrive in suite order, and the order leads with the four figures a \
                    configuration is judged structurally on: the density, then the single points of \
                    failure as nodes, as arcs, and as their total. The two halves are \
                    reported beside the total because they suggest different remedies — an \
                    indispensable facility is answered with a second site, an indispensable lane \
                    with a second route.

                    **A GET that writes.** The suite is persisted as it is returned, against the \
                    network with `runId = null`, because a topological metric belongs to the \
                    structure rather than to any run. A recompute replaces the network's \
                    previous run-less rows; rows belonging to a simulation run are never touched. \
                    The write is what lets the comparison view read a variant's suite \
                    without opening it, and it is idempotent — the same network yields the same \
                    values and the same row count, so calling this twice leaves the database in the \
                    state one call would.

                    Values are exact, so `ciLow` and `ciHigh` are null: a confidence interval is a \
                    property of aggregating replications, which topological metrics do not do.

                    Computed on a network that a simulation run has frozen, too. A frozen network \
                    cannot change, so its structural metrics cannot either — refusing here would \
                    withhold the suite from precisely the configurations that have been evaluated.

                    `computedInMs` reports the wall-clock cost, against the two-second budget \
                    FR-04 sets at 1,000 nodes. `NODE_CRITICALITY` dominates it: the definition \
                    costs one maximum-flow computation per node.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The suite, computed and persisted.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TopologicalMetricsResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/metrics/topological")
    public TopologicalMetricsResponse topological(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId) {
        return metrics.computeAndPersist(networkId, currentUser.ownerId());
    }
}
