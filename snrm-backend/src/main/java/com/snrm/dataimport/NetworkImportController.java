package com.snrm.dataimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snrm.auth.CurrentUser;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

/**
 * CSV/XLSX network import — the two requests behind the wizard (FR-02).
 *
 * <p><strong>The path.</strong> The obvious path would be {@code POST /networks/{id}/import}, but an
 * import <em>creates</em> a network — a network is created only if validation passes, so no partially
 * imported networks can exist — and there is therefore no {@code {id}} to put in the path at the
 * time of the call. The endpoint is {@code POST /networks/import} with the project named in the body,
 * which is the only shape that works — a deliberate deviation from the obvious spelling of the
 * path.
 *
 * <p><strong>Two requests, no server-side state.</strong> The preview parses and answers with the
 * headers and a proposed mapping, so the wizard can draw its mapping step. The import parses again with
 * the confirmed mapping. The files are re-sent rather than stashed server-side: there is then no upload
 * to expire, no temporary storage to authorise a second time, and no possibility of the confirmed import
 * being validated against a different file than the one the user was looking at.
 *
 * <p><strong>A failed validation is a 200.</strong> The report is the answer the wizard renders,
 * and RFC 7807 has nowhere to put a hundred structured rows. A malformed <em>request</em>
 * — no files, an unknown project, unparseable mapping JSON — still comes back as {@code problem+json}.
 */
@Tag(name = "Data import",
        description = "Create a network from CSV files or an Excel workbook, with the two-stage "
                + "validation report (FR-02).")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class NetworkImportController {

    private final ImportService imports;
    private final ObjectMapper json;
    private final CurrentUser currentUser;

    NetworkImportController(ImportService imports, ObjectMapper json, CurrentUser currentUser) {
        this.imports = imports;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Inspect an upload and propose a column mapping",
            description = """
                    Step 1→2 of the import wizard. Parses the upload and answers with what \
                    it contains — the sheets, their headers, their row counts, a sample of rows — and \
                    the mapping to propose: each source column paired with the canonical column \
                    it matches, or null where nothing matched.

                    Nothing is validated and nothing is stored. Send the files again to \
                    `POST /networks/import` with the mapping the user confirmed.

                    Send either a set of CSV files named after the canonical sheets — `nodes.csv`, \
                    `links.csv`, `products.csv`, `node_products.csv`, `network_meta.csv` — or one \
                    `.xlsx` workbook whose sheet names match. Names are matched case-insensitively \
                    and tolerate spaces and hyphens. CSV delimiters are auto-detected among `,` `;` \
                    tab and `|`.

                    `timeBase.declared` is false when no `network_meta` sheet was supplied, in which \
                    case the wizard must confirm the 1 DAY / 52 / NEAREST defaults before importing: \
                    every value in a column without a `*_unit` column is read in the period's unit.\
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What the upload contains.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ImportPreview.class))),
            @ApiResponse(responseCode = "400", description = "No files, or `mapping` is not valid JSON.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/networks/import/preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview preview(
            // Optional so that an upload with nothing attached gets the message below rather than
            // Spring's "required parameter 'files' is not present", which does not say what to attach.
            @Parameter(description = "The CSV files, or one .xlsx workbook.")
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @Parameter(description = "Optional column mapping to apply before answering, so the "
                    + "wizard can show the effect of a change: "
                    + "`{\"nodes\": {\"Facility\": \"name\"}}`.")
            @RequestParam(value = "mapping", required = false) String mapping) {

        requireFiles(files);
        return imports.preview(files, ImportMapping.parse(mapping, json));
    }

    @Operation(summary = "Validate an upload and create the network",
            description = """
                    The two-stage validation — row level, then network level — and, unless \
                    `validateOnly` is set, the network it describes.

                    **Transactional.** A network is created only if no diagnostic is an error, so no \
                    partially imported network can exist. Read `committed` rather than inferring it: \
                    a dry run that finds nothing wrong is `valid` and not `committed`, which is \
                    exactly what step 3 of the wizard shows before the user confirms.

                    **Validation failure is a 200, not a problem document.** The report is the answer: \
                    it lists every finding with its sheet, line and column so the user can fix the \
                    file or correct the value in the wizard. Only a malformed request is a \
                    4xx.

                    **Stage 2 includes the resolution checks**, run in `IMPORT` context, \
                    where a duration that converts to zero periods is an error rather than a warning — \
                    a 6-hour lead time on a network stepping in days would silently become \
                    instantaneous. They are listed in `diagnostics` with their `TimeCheck` codes and \
                    carried whole in `timeValidation`, which also holds the suggested period.

                    **Units.** A `*_unit` column may be omitted, in which case the value is read in the \
                    network's period unit — so a plain numeric file imports exactly as it did before \
                    units existed. Unit tokens are case-insensitive and accept abbreviations \
                    (`h`, `hr`, `hrs`, `hour`, `hours`).

                    The version number is the server's, as for `POST /projects/{id}/networks`: the \
                    first network of a name is version 1 and each later one takes the next. Importing \
                    an edited export under the same name therefore adds a variant, which is the \
                    export → edit → re-import round trip the importer supports.

                    **`baseNetworkId` records the new network as a configuration variant** of that \
                    network (FR-28), written as a `CONFIGURATION_VARIANT` in the same transaction \
                    that creates it — so an imported variant is never briefly a network with no \
                    edge, and a rolled-back import leaves no orphan edge either. The base must be a \
                    network of the same project. Nothing is diffed: `leverChanges` is the note the \
                    request carries or nothing at all, because this tool does not compare two \
                    networks. `validateOnly=true` records no edge, for the same reason it creates no \
                    network. Read it back through `GET /networks/{baseNetworkId}/variants`.

                    **A batch of files is N of these requests**, sequenced by the wizard and not by \
                    a batch endpoint: the two-stage report is per network and so is the transaction, \
                    which is what lets eight workbooks with a bad row in the sixth import seven and \
                    name the sixth. Import the baseline first — a variant edge cannot name \
                    a base that does not exist yet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the new "
                    + "network and the report carries it.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ImportReport.class))),
            @ApiResponse(responseCode = "200", description = "Validated and not created — either a dry "
                    + "run, or the report holds errors.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ImportReport.class))),
            @ApiResponse(responseCode = "400", description = "No files, a missing name, a period "
                    + "length without a unit, or unparseable `mapping`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user, or no "
                    + "such `baseNetworkId`.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`BASELINE_ALREADY_SET` — the project "
                    + "already has a baseline.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "`BASE_NETWORK_OUT_OF_SCOPE` — the "
                    + "named base network belongs to another project (FR-28). Nothing is created; a "
                    + "variant edge only means anything within one project.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(path = "/api/v1/networks/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportReport> importNetwork(
            @Parameter(description = "The CSV files, or one .xlsx workbook.")
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @Parameter(description = "Project to create the network in. Products resolve within it.",
                    example = "1")
            @RequestParam("projectId") long projectId,
            @Parameter(description = "Name for the new network; the server assigns the version. May be "
                    + "omitted when the file declares one — an XML export always does, and "
                    + "network_meta has an optional `name` column.",
                    example = "Imported baseline")
            @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "Mark it as the project's baseline. At most one per project. "
                    + "Distinct from `baseNetworkId`: this is the project's baseline flag, that is "
                    + "the network this import is a variant of.")
            @RequestParam(value = "baseline", defaultValue = "false") boolean baseline,
            @Parameter(description = "Record the new network as a CONFIGURATION_VARIANT of this "
                    + "network, in the same transaction that creates it (FR-28). Must be a network "
                    + "of the same project. Omit for an independent network.", example = "7")
            @RequestParam(value = "baseNetworkId", required = false) Long baseNetworkId,
            @Parameter(description = "Note to store on that variant edge — the lever the file "
                    + "changes, as the fork prompt collects it: `{\"note\": \"+20% capacity at "
                    + "PLANT-1\"}`. Nothing is computed: this tool does not diff two networks. "
                    + "Ignored without a baseNetworkId.")
            @RequestParam(value = "leverChanges", required = false) String leverChanges,
            @Parameter(description = "Validate and report without creating anything — the wizard's "
                    + "report step.")
            @RequestParam(value = "validateOnly", defaultValue = "false") boolean validateOnly,
            @Parameter(description = "Column mapping from the wizard's mapping step: "
                    + "`{\"nodes\": {\"Facility\": \"name\"}}`. Omit when the file's headers are "
                    + "already canonical.")
            @RequestParam(value = "mapping", required = false) String mapping,
            @Parameter(description = "Confirmed period length — the wizard's answer when no "
                    + "network_meta sheet was supplied. Goes with periodLengthUnit and overrides the "
                    + "file.", example = "1")
            @RequestParam(value = "periodLengthValue", required = false) Double periodLengthValue,
            @Parameter(description = "Unit of the confirmed period length. Case-insensitive, and "
                    + "accepts the same abbreviations the file's unit columns do — `h`, `hrs`, `DAY`.",
                    example = "DAY")
            @RequestParam(value = "periodLengthUnit", required = false) String periodLengthUnit,
            @Parameter(description = "Confirmed horizon in periods.", example = "52")
            @RequestParam(value = "horizonPeriods", required = false) Integer horizonPeriods,
            @Parameter(description = "Confirmed rounding policy: NEAREST, UP or DOWN.",
                    example = "NEAREST")
            @RequestParam(value = "roundingPolicy", required = false) String roundingPolicy) {

        requireFiles(files);
        // A blank name is not refused here: the file may carry one, and whether it does is only known
        // once it has been parsed. ImportService.nameOf makes the call and raises a 400 if neither has.
        ImportRequest request = new ImportRequest(projectId, name, baseline, validateOnly,
                ImportRequest.periodOf(periodLengthValue, timeUnit(periodLengthUnit)),
                horizonPeriods, roundingPolicy(roundingPolicy), ImportMapping.parse(mapping, json),
                baseNetworkId, leverChanges(leverChanges));

        ImportReport report = imports.importNetwork(files, request, currentUser.ownerId());
        if (!report.committed()) {
            // 200 rather than 422: for a dry run nothing was meant to happen, and for a failed
            // validation the body is a report the client is expected to render, not an error it should
            // treat as a transport failure.
            return ResponseEntity.ok(report);
        }
        return ResponseEntity
                .created(URI.create("/api/v1/networks/" + report.networkId()))
                .body(report);
    }

    /**
     * The {@code leverChanges} form field as JSON (FR-28).
     *
     * <p>A JSON string in a flat form field, exactly as {@code mapping} is and for the same reason:
     * a browser's {@code FormData} appends strings, and the note is a structured value with no flat
     * spelling. Malformed JSON is a 400 rather than a note stored as a quoted string — the column is
     * a MySQL {@code JSON} column and the comparison view renders these as real JSON, so
     * accepting text here would move the failure to a reader that cannot do anything about it.
     *
     * @return the parsed note, or null when the field was absent — which is the ordinary case, since
     *         nothing computes one
     */
    private JsonNode leverChanges(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException(("leverChanges is not valid JSON: %s. Send an object "
                    + "such as {\"note\": \"+20%% capacity at PLANT-1\"}, or omit the field — a "
                    + "variant edge with no note is an ordinary edge.")
                    .formatted(malformed.getOriginalMessage()), malformed);
        }
    }

    /**
     * A unit form field, read with the same tolerance the file's unit columns get ({@link UnitTokens}).
     *
     * <p>Deliberately not left to Spring's enum converter, which is case-sensitive: the wizard sends the
     * literal, but a researcher exercising the endpoint by hand types {@code day}, and there is no reason
     * for the query parameter to be stricter than the spreadsheet cell.
     */
    private static TimeUnit timeUnit(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UnitTokens.timeUnit(value).orElseThrow(() -> new IllegalArgumentException(
                "periodLengthUnit '%s' is not a time unit. Accepted: %s."
                        .formatted(value, UnitTokens.acceptedTimeUnitTokens())));
    }

    private static RoundingPolicy roundingPolicy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UnitTokens.roundingPolicy(value).orElseThrow(() -> new IllegalArgumentException(
                "roundingPolicy '%s' is not one of NEAREST, UP, DOWN.".formatted(value)));
    }

    private static void requireFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new IllegalArgumentException("Attach at least one file: the CSV set "
                    + "(nodes.csv, links.csv, products.csv, node_products.csv, network_meta.csv) or "
                    + "one .xlsx workbook whose sheets carry those names.");
        }
    }
}
