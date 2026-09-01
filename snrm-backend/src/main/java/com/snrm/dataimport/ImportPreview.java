package com.snrm.dataimport;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * What {@code POST /networks/import/preview} answers: what the upload turned out to contain, and the
 * mapping the wizard should propose.
 *
 * <p><strong>Why the preview is its own request.</strong> Step 2 of the wizard maps source columns onto
 * canonical ones, and it cannot draw that screen without knowing the file's headers — but reading the
 * headers means parsing, which is server work. The alternative, parsing the file in the browser to draw
 * step 2 and again on the server to validate it, would put two independent CSV and XLSX parsers in the
 * system and make "the delimiter was detected differently" a class of bug. So the server parses once
 * for the preview and once for the validated import, and the browser parses nothing.
 *
 * <p>Nothing is stored between the two calls. The files stay client-side and are posted again with the
 * confirmed mapping, which keeps the import stateless — no temporary upload to expire, garbage-collect
 * or authorise a second time.
 *
 * @param sheets      one entry per canonical sheet found, in schema order
 * @param declaredName the network's own name if the file carries one — an XML export always does
 *                    — so the wizard can offer it rather than asking for a name the file
 *                    already knows. Null otherwise.
 * @param timeBase    the clock read from {@code network_meta}, or the defaults for the wizard to
 *                    confirm
 * @param schema      every canonical sheet and column, so the wizard's dropdowns come from the server
 *                    rather than from a second copy of the schema in the client
 * @param rowsRead    data rows found per sheet
 * @param diagnostics anything already visible without validating rows: an unreadable file, an
 *                    unrecognised sheet, a missing required column
 */
@Schema(name = "ImportPreview",
        description = "What an uploaded CSV set or workbook contains, and the column mapping to "
                + "propose in the wizard's mapping step.")
public record ImportPreview(

        @Schema(description = "One entry per recognised canonical sheet, in schema order.")
        List<SheetPreview> sheets,

        @Schema(description = "The network name the file declares, if any. The wizard offers it as "
                + "the default so an XML export can be re-imported without typing anything.",
                example = "Baseline", nullable = true)
        String declaredName,

        @Schema(description = "The time base the import would use.")
        ImportTimeBase timeBase,

        @Schema(description = "The canonical schema, keyed by sheet name — the options the "
                + "mapping step offers.")
        Map<String, List<CanonicalColumn>> schema,

        @Schema(description = "Data rows found per sheet, header excluded.", example = "{\"nodes\": 6}")
        Map<String, Integer> rowsRead,

        @Schema(description = "Problems visible before any row is validated, worst first.")
        List<ImportDiagnostic> diagnostics) {

    public ImportPreview {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        schema = schema == null ? Map.of() : Map.copyOf(schema);
        rowsRead = rowsRead == null ? Map.of() : Map.copyOf(rowsRead);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * One sheet of the upload.
     *
     * @param sheet      canonical sheet name — {@code nodes}
     * @param sourceName the file or worksheet it came from
     * @param origin     how it was read: {@code CSV (delimiter ';')}, {@code XLSX}
     * @param headers    its header row, exactly as found and in file order
     * @param rowCount   data rows
     * @param columns    one entry per source header with the canonical column it would feed
     * @param missingRequired required columns of this sheet that nothing feeds yet
     * @param sampleRows the first few rows, so the user can see what they are mapping
     */
    @Schema(name = "ImportSheetPreview")
    public record SheetPreview(

            @Schema(description = "Canonical sheet name.", example = "nodes")
            String sheet,

            @Schema(description = "The file or worksheet this came from.", example = "nodes.csv")
            String sourceName,

            @Schema(description = "How it was read, including the detected CSV delimiter.",
                    example = "CSV (delimiter ',')")
            String origin,

            @Schema(description = "The header row as found, in file order.")
            List<String> headers,

            @Schema(description = "Data rows, header excluded.", example = "6")
            int rowCount,

            @Schema(description = "Each source column and the canonical one it would feed.")
            List<ColumnSuggestion> columns,

            @Schema(description = "Required columns nothing feeds yet — an error unless the mapping "
                    + "step fixes it.")
            List<String> missingRequired,

            @Schema(description = "The first few data rows, in header order, for the mapping preview.")
            List<List<String>> sampleRows) {

        public SheetPreview {
            headers = headers == null ? List.of() : List.copyOf(headers);
            columns = columns == null ? List.of() : List.copyOf(columns);
            missingRequired = missingRequired == null ? List.of() : List.copyOf(missingRequired);
            sampleRows = sampleRows == null ? List.of() : List.copyOf(sampleRows);
        }
    }

    /**
     * One source column and what it would become.
     *
     * @param sourceColumn    the header as it appears in the file
     * @param canonicalColumn the canonical column it feeds, or null when nothing matched — which is
     *                        exactly the case the mapping step exists for
     */
    @Schema(name = "ImportColumnSuggestion")
    public record ColumnSuggestion(

            @Schema(description = "Header as it appears in the file.", example = "Failure Prob")
            String sourceColumn,

            @Schema(description = "Canonical column this would feed. Null when nothing matched: the "
                    + "column is ignored unless the user maps it.",
                    example = "failure_prob", nullable = true)
            String canonicalColumn) {
    }

    /**
     * One canonical column, as the mapping step needs to describe it.
     *
     * @param name      the canonical header, exactly as {@code ImportSchema} spells it
     * @param required  whether the sheet is invalid without it
     * @param unitOwner for a {@code *_unit} column, the value column it qualifies
     * @param note      one line of guidance for the wizard
     */
    @Schema(name = "ImportCanonicalColumn")
    public record CanonicalColumn(

            @Schema(description = "The canonical column name.", example = "capacity_time_unit")
            String name,

            @Schema(description = "Whether the sheet is invalid without it.", example = "false")
            boolean required,

            @Schema(description = "For a unit column, the value column it qualifies. Omitting a unit "
                    + "column means the value is in the network's period unit.",
                    example = "capacity_value", nullable = true)
            String unitOwner,

            @Schema(description = "One line of guidance shown beside the column in the wizard.")
            String note) {
    }
}
