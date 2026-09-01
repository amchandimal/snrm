package com.snrm.dataimport;

import com.snrm.network.NetworkDto;
import com.snrm.network.TimeValidationReport;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * What {@code POST /networks/import} answers, whether it created a network or refused to.
 *
 * <p><strong>Why a failed validation is a 200 and not a problem document.</strong> The report <em>is</em>
 * the answer: step 3 of the wizard is a table of per-row errors and warnings that the user reads,
 * corrects and re-submits, and RFC 7807 has nowhere to put a hundred structured rows. A
 * malformed <em>request</em> — no files, an unknown project, unparseable mapping JSON — is a different
 * thing and does come back as {@code problem+json}. The distinction is whether the user's data was
 * understood well enough to have an opinion about.
 *
 * <p><strong>{@link #committed()} and {@link #valid()} are not the same question.</strong> A dry run
 * ({@code validateOnly=true}) that finds nothing wrong is valid and not committed — that is the whole
 * of step 3, which shows the report before the user confirms. Only a request that both passed and asked
 * to commit carries a {@link #network()}.
 *
 * @param valid          true when nothing in {@link #diagnostics()} is an error
 * @param committed      true when a network was actually created — false for every dry run
 * @param networkId      the created network's id, or null
 * @param network        the created network, so the wizard can open the editor without a second call
 * @param timeBase       the clock the import used or would use
 * @param timeValidation the resolution findings under that clock, run in IMPORT context
 * @param rowsRead       data rows read per sheet, header excluded
 * @param errorCount     how many diagnostics block the import
 * @param warningCount   how many do not
 * @param graphComplete  false when row errors dropped rows before the network-level checks ran, so
 *                       those findings describe a partial graph
 * @param diagnostics    every finding, worst first
 */
@Schema(name = "ImportReport",
        description = "The two-stage validation report, and the network if one was "
                + "created. Imports are transactional: a network exists only when `committed` is true.")
public record ImportReport(

        @Schema(description = "True when no diagnostic is an error.", example = "false")
        boolean valid,

        @Schema(description = "True when a network was created. Always false for a dry run "
                + "(`validateOnly=true`), which is how the wizard fills its report step before the "
                + "user confirms.", example = "false")
        boolean committed,

        @Schema(description = "Id of the created network.", example = "12", nullable = true)
        Long networkId,

        @Schema(description = "The created network, so the wizard can open it in the editor without a "
                + "second request.", nullable = true)
        NetworkDto network,

        @Schema(description = "The clock the import used, or would use.")
        ImportTimeBase timeBase,

        @Schema(description = "The resolution findings for that clock, evaluated in "
                + "IMPORT context — where a duration that converts to zero periods is an error. Also "
                + "carries the suggested period, which is the remedy to offer.", nullable = true)
        TimeValidationReport timeValidation,

        @Schema(description = "Data rows read per sheet, header excluded.",
                example = "{\"nodes\": 6, \"links\": 6}")
        Map<String, Integer> rowsRead,

        @Schema(description = "How many diagnostics block the import.", example = "3")
        int errorCount,

        @Schema(description = "How many diagnostics are warnings.", example = "2")
        int warningCount,

        @Schema(description = "False when row-level errors dropped rows before the network-level "
                + "checks ran, so those findings describe the graph without them.", example = "true")
        boolean graphComplete,

        @Schema(description = "Every finding, errors first, then by sheet in schema order and by line.")
        List<ImportDiagnostic> diagnostics) {

    public ImportReport {
        rowsRead = rowsRead == null ? Map.of() : Map.copyOf(rowsRead);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
