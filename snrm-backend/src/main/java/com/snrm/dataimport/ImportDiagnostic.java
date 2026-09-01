package com.snrm.dataimport;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One thing wrong with an import, addressed precisely enough to fix.
 *
 * <blockquote>"reported as a per-row error table with line numbers so the user can fix the source file
 * or correct values inline in the wizard."</blockquote>
 *
 * <p>Which is why this is structured rather than a sentence. {@link #sheet()}, {@link #line()} and
 * {@link #column()} are what let the wizard put the message against the offending cell; {@link #code()}
 * is what lets it decide the message is fixable in the mapping step rather than in the file; and
 * {@link #value()} echoes the cell back so a report can be read without the file open beside it.
 *
 * <p>Null members are omitted from the JSON ({@code spring.jackson.default-property-inclusion=non_null}),
 * which is how a network-level finding says it is not about any one row: stage 2 asks questions
 * about the whole graph, and "customer C-3 has no inbound path" has no line number to give.
 *
 * @param sheet    the canonical sheet — {@code nodes}, {@code node_products} — or null for a finding
 *                 about the upload as a whole
 * @param line     the 1-based line as the user's editor numbers it, header included; null for a
 *                 sheet-level or network-level finding
 * @param column   the canonical column at fault, or the source header for a mapping problem
 * @param severity error blocks the import; warning does not
 * @param code     which rule fired — the machine-readable contract
 * @param message  the sentence shown to the user
 * @param value    the offending cell as it was written, for the inline-correction table
 * @param element  the network element a stage-2 finding is about — a node name, {@code A → B} for a
 *                 link — so the report can name it where there is no row to point at
 */
@Schema(name = "ImportDiagnostic",
        description = "One row-level or network-level problem found while validating an import.")
public record ImportDiagnostic(

        @Schema(description = "The canonical sheet the finding is about. Absent for a finding about "
                + "the upload as a whole.", example = "nodes", nullable = true)
        String sheet,

        @Schema(description = "1-based line number in the source file or worksheet, header included, "
                + "so it matches what the user's editor shows. Absent for sheet- and network-level "
                + "findings.", example = "7", nullable = true)
        Integer line,

        @Schema(description = "The canonical column at fault, or the source header when the problem "
                + "is the mapping itself.", example = "failure_prob", nullable = true)
        String column,

        @Schema(description = "ERROR blocks the import — nothing is written. WARNING does "
                + "not.", example = "ERROR")
        ImportSeverity severity,

        @Schema(description = "Which rule fired. Stable machine code; branch on this, never on the "
                + "message. Values are the names of `ImportCheck` for the two stages, and "
                + "of `TimeCheck` for the resolution findings folded in beside them — "
                + "one namespace, and the two enums do not collide.",
                example = "PROBABILITY_OUT_OF_RANGE")
        String code,

        @Schema(description = "The sentence to show the user.",
                example = "failure_prob must be between 0 and 1, was 1.4.")
        String message,

        @Schema(description = "The offending cell exactly as written, for the inline-correction "
                + "table.", example = "1.4", nullable = true)
        String value,

        @Schema(description = "The network element a network-level finding is about — a node's name, "
                + "or a link's two endpoints.", example = "CUST-2", nullable = true)
        String element) {

    /** A finding about one cell: the common case of stage 1. */
    static ImportDiagnostic cell(ImportSheet sheet, int line, String column, ImportSeverity severity,
            ImportCheck code, String value, String message) {
        return new ImportDiagnostic(sheet.canonicalName(), line, column, severity, code.name(),
                message, value, null);
    }

    /** A finding about a whole row — a duplicate key, a self-loop — rather than one of its cells. */
    static ImportDiagnostic row(ImportSheet sheet, int line, ImportSeverity severity,
            ImportCheck code, String element, String message) {
        return new ImportDiagnostic(sheet.canonicalName(), line, null, severity, code.name(),
                message, null, element);
    }

    /** A finding about a sheet: a missing required column, an absent optional sheet. */
    static ImportDiagnostic sheet(ImportSheet sheet, String column, ImportSeverity severity,
            ImportCheck code, String message) {
        return new ImportDiagnostic(sheet.canonicalName(), null, column, severity, code.name(),
                message, null, null);
    }

    /** A finding about the whole upload — an unreadable file, a missing sheet. */
    static ImportDiagnostic upload(ImportSeverity severity, ImportCheck code, String element,
            String message) {
        return new ImportDiagnostic(null, null, null, severity, code.name(), message, null, element);
    }

    /** A stage-2 finding about a named element of the graph, with no row to point at. */
    static ImportDiagnostic network(ImportSheet sheet, ImportSeverity severity, ImportCheck code,
            String element, String message) {
        return new ImportDiagnostic(sheet == null ? null : sheet.canonicalName(), null, null,
                severity, code.name(), message, null, element);
    }

    /**
     * A resolution finding, mirrored into this report so the per-row table shows it
     * beside the cell problems.
     *
     * <p>The code comes from {@code TimeCheck} rather than {@link ImportCheck}, deliberately: those
     * four codes are already contract with the editor's warning banner, and giving the same
     * check a second name for the wizard would mean a client could not treat "6 h rounds to nothing"
     * as one concept. The authoritative form of these findings is still
     * {@code ImportReport.timeValidation}, which carries the declared value, the converted periods and
     * the suggested period; this is the row-addressed echo of it.
     *
     * @param code the {@code TimeCheck} constant's name
     */
    static ImportDiagnostic timeResolution(ImportSheet sheet, Integer line, String column,
            ImportSeverity severity, String code, String element, String message) {
        return new ImportDiagnostic(sheet == null ? null : sheet.canonicalName(), line, column,
                severity, code, message, null, element);
    }

    /** The same finding with a line number attached, for a stage-2 rule that can name its row. */
    ImportDiagnostic atLine(Integer atLine) {
        return new ImportDiagnostic(sheet, atLine, column, severity, code, message, value, element);
    }

    boolean isError() {
        return severity == ImportSeverity.ERROR;
    }
}
