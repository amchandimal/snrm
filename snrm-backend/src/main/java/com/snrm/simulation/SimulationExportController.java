package com.snrm.simulation;

import com.snrm.auth.CurrentUser;
import com.snrm.common.TabularExport;
import com.snrm.common.TabularFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * A run's results as a spreadsheet — the export button on the results dashboard.
 *
 * <p>A controller of its own rather than a fifth method on {@link SimulationController}, following
 * {@code NetworkExportController}: that class declares {@code produces = application/json} for every
 * method it holds, and this one answers with a file.
 */
// Name only, no description: SimulationController already describes this tag, and two descriptions
// under one name leave springdoc to pick between them.
@Tag(name = "Simulations")
@RestController
public class SimulationExportController {

    private final SimulationExportService exports;
    private final CurrentUser currentUser;

    SimulationExportController(SimulationExportService exports, CurrentUser currentUser) {
        this.exports = exports;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Export a run's results",
            description = """
                    The results dashboard as a spreadsheet: three tables — `run`, `metrics` and \
                    `timeseries` — as one `.xlsx` workbook or a zip of three CSV files.

                    **`run`** is provenance: the network and scenario evaluated, the status and \
                    timestamps, the network's period length and horizon, and `params_json` \
                    verbatim — the seed included, which is the whole of the reproducibility claim. \
                    An exported result whose seed is missing cannot be re-derived.

                    **`metrics`** is the suite with its 95% intervals. A time-valued metric is \
                    written twice: `value` in periods, and `value_in_display_unit` beside its unit, \
                    so `TTR` reads as 14 and as 14 days rather than as a bare number nobody can \
                    interpret a month later.

                    **`timeseries`** is the figure's data, and two columns more. Beside the \
                    disrupted and baseline curves it carries `fill_rate_loss` — their difference, \
                    period by period — which is the height of the resilience triangle at each \
                    period and what the shaded area is drawn from. Note that summing it \
                    gives a *lower bound* on `LOSS_AREA` rather than the metric: these rows are \
                    replication averages, and the metric takes the shortfall per replication before \
                    averaging, which is the same quantity with the mean and the `max(0, …)` in the \
                    other order. The two coincide on a deterministic run. `cost_delta` is linear \
                    and does sum exactly to `DISRUPTION_COST_DELTA`.

                    A run that is still queued or executing exports its `run` table and two empty \
                    ones, matching `GET /simulations/{runId}`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The workbook or the zipped CSV set.",
                    content = {
                            @Content(mediaType = "application/vnd.openxmlformats-officedocument"
                                    + ".spreadsheetml.sheet",
                                    schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "application/zip",
                                    schema = @Schema(type = "string", format = "binary"))
                    }),
            @ApiResponse(responseCode = "400", description = "Unrecognised `format`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such run for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/simulations/{runId}/export")
    public ResponseEntity<Resource> export(
            @Parameter(description = "Run id.", example = "12") @PathVariable long runId,
            @Parameter(description = "`xlsx` for one workbook, `csv` for a zip of the three tables.",
                    example = "xlsx")
            @RequestParam(value = "format", defaultValue = "xlsx") String format) {

        TabularExport.File export = exports.export(runId, currentUser.ownerId(),
                TabularFormat.of(format));

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(export.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_TYPE, export.contentType())
                .contentLength(export.content().length)
                .body(new ByteArrayResource(export.content()));
    }
}
