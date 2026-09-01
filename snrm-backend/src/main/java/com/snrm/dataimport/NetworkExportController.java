package com.snrm.dataimport;

import com.snrm.auth.CurrentUser;
import com.snrm.dataimport.NetworkExportService.Export;
import com.snrm.dataimport.NetworkExportService.Format;
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
 * Network export in the canonical schema — the other half of the round trip.
 *
 * <blockquote>"the same canonical schema is used by the export function, making round-tripping (export →
 * edit in Excel → re-import as a new variant) a supported workflow."</blockquote>
 *
 * <p>Which is the whole point of the endpoint, and why it lives beside the importer rather than in the
 * network module: the two share {@link ImportSchema}, and separating them would be the first step towards
 * two lists of column names.
 *
 * <p>To re-import an export, post the file back to {@code POST /networks/import}. Under the same network
 * name it becomes the next version — a configuration variant of what was exported.
 */
@Tag(name = "Data import",
        description = "Create a network from CSV files or an Excel workbook, with the two-stage "
                + "validation report (FR-02).")
@RestController
public class NetworkExportController {

    private final NetworkExportService exports;
    private final CurrentUser currentUser;

    NetworkExportController(NetworkExportService exports, CurrentUser currentUser) {
        this.exports = exports;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Export a network in the canonical import schema",
            description = """
                    The network as the schema spells it: one `.xlsx` workbook with the five canonical \
                    sheets — `network_meta`, `nodes`, `links`, `products`, `node_products` — a zip of \
                    the same five as CSV files, or the single XML document.

                    **Which to pick.** `xlsx` to edit the network by hand and re-import it. `csv` to \
                    diff two variants as text. `xml` to archive or send a network: it is one \
                    self-describing file, it needs no column mapping on the way back in, and it is \
                    the format to attach to a paper or commit beside a results set.

                    **Unit-preserving.** Every duration and rate is written as the value the user \
                    entered plus the unit they chose, in its own `*_unit` column — or, in XML, as a \
                    `<leadTime value="36" unit="HOUR"/>` child element. A lead time held as 36 HOUR \
                    comes out as 36 and HOUR, not as 1.5 days and not as a bare 36 to be re-read in \
                    the period's unit.

                    **Layout-preserving.** `pos_x`/`pos_y` are written in every format, so a network \
                    arranged by hand on the canvas comes back arranged. A node the editor \
                    never positioned exports empty and re-imports as "no layout", which the editor \
                    answers with auto-layout rather than stacking it on the origin.

                    **Round trip.** Post the file back to `POST /networks/import` to create a network \
                    from it. Under the same name it takes the next version and is a configuration \
                    variant of the original; under a new name it is a new network.

                    Only the products this network references are written, not the whole project \
                    catalogue: products are project-scoped, and an export carrying unrelated ones \
                    would import them into whatever project it landed in.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The workbook, the zipped CSV set, or the XML document.",
                    content = {
                            @Content(mediaType = "application/vnd.openxmlformats-officedocument"
                                    + ".spreadsheetml.sheet",
                                    schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "application/zip",
                                    schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "application/xml",
                                    schema = @Schema(type = "string", format = "binary"))
                    }),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such network for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/networks/{networkId}/export")
    public ResponseEntity<Resource> export(
            @Parameter(description = "Network id.", example = "1") @PathVariable long networkId,
            @Parameter(description = "`xlsx` for one workbook, `csv` for a zip of the five files, "
                    + "`xml` for the interchange document.", example = "xlsx")
            @RequestParam(value = "format", defaultValue = "xlsx") String format) {

        Export export = exports.export(networkId, currentUser.ownerId(), Format.of(format));

        // Both filename and filename* are set: the ASCII fallback keeps old clients working and the
        // UTF-8 form carries a network name with an accent in it intact.
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
