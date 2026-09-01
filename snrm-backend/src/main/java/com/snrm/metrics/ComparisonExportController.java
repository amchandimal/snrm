package com.snrm.metrics;

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
import java.util.List;

/**
 * The comparison matrix as a spreadsheet — the export button on the comparison view.
 *
 * <p>Takes the same query parameters as {@link ComparisonController} and produces the same matrix in
 * a file, so exporting is "what I am looking at, saved" rather than a second query with its own
 * defaults.
 */
// Name only: ComparisonController describes this tag (see SimulationExportController on why).
@Tag(name = "Comparison")
@RestController
public class ComparisonExportController {

    private final ComparisonExportService exports;
    private final CurrentUser currentUser;

    ComparisonExportController(ComparisonExportService exports, CurrentUser currentUser) {
        this.exports = exports;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Export the comparison matrix",
            description = """
                    The comparison view as a spreadsheet: three tables — `comparison`, `variants` \
                    and `notes` — as one `.xlsx` workbook or a zip of three CSV files. Same \
                    parameters as `GET /projects/{id}/comparison`, same columns in the same order, \
                    same winners, same converted units.

                    **`comparison`** is the matrix. Each variant occupies three columns — value, \
                    `ci_low`, `ci_high` — so every cell stays a number a reader can sort and chart; \
                    folding the interval into one string would make the export decorative. A blank \
                    cell is unmeasured, never zero. The `best` column names the winning variant, or \
                    the tied ones, and is empty for a `NEUTRAL` metric — one that describes a \
                    configuration without ranking it.

                    **`variants`** is the column legend, carrying `lever_changes` — the structured \
                    diff that says what was changed to produce each configuration. \
                    That column is what makes an exported matrix a finding rather than a scoreboard.

                    **`notes`** travels with the file deliberately. A matrix separated from its \
                    "these configurations use different period lengths" warning is a matrix that \
                    will be misread.""")
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
            @ApiResponse(responseCode = "404",
                    description = "No such project for this user, or a named network is not in it.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/projects/{projectId}/comparison/export")
    public ResponseEntity<Resource> export(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId,

            @Parameter(description = "The configurations to compare, in column order. Omit to "
                    + "export every network in the project.", example = "1,4,5")
            @RequestParam(value = "networkIds", required = false) List<Long> networkIds,

            @Parameter(description = "Pin every column to this disruption scenario.", example = "1")
            @RequestParam(value = "scenarioId", required = false) Long scenarioId,

            @Parameter(description = "Export the run-keyed matrix instead — one column per run "
                    + "(FR-17). Mutually exclusive with networkIds and scenarioId, exactly as on "
                    + "the view.", example = "12,13")
            @RequestParam(value = "runIds", required = false) List<Long> runIds,

            @Parameter(description = "`xlsx` for one workbook, `csv` for a zip of the three tables.",
                    example = "xlsx")
            @RequestParam(value = "format", defaultValue = "xlsx") String format) {

        TabularExport.File export;
        if (runIds != null && !runIds.isEmpty()) {
            // The identical rule the view enforces: an export is "what I am looking at, saved",
            // and it must be refused for exactly the requests the view refuses.
            ComparisonController.requireRunSelectorAlone(networkIds, scenarioId);
            export = exports.exportRuns(projectId, currentUser.ownerId(), runIds,
                    TabularFormat.of(format));
        } else {
            export = exports.export(projectId, currentUser.ownerId(), networkIds,
                    scenarioId, TabularFormat.of(format));
        }

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
