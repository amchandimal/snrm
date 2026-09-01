package com.snrm.metrics;

import com.snrm.common.TabularExport;
import com.snrm.common.TabularExport.Table;
import com.snrm.common.TabularFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes the comparison matrix out as a spreadsheet — the export button on the comparison view
 * (FR-10).
 *
 * <p>Built from {@link ComparisonService#compare} and nothing else. The export is the same object
 * the view renders, laid flat: same columns in the same order, same winners, same converted units,
 * same caveats. That is the whole design constraint — an export that recomputed the matrix would be
 * a second implementation of "which cell wins", and the two would eventually disagree in a table
 * somebody had already put in a paper.
 *
 * <p>Three tables. {@code comparison} is the matrix itself; {@code variants} is the column legend,
 * carrying the lever diff that explains each column; {@code notes} is the caveats,
 * which travel with the file because a matrix separated from its "these variants use different
 * period lengths" warning is a matrix that will be misread.
 */
@Service
public class ComparisonExportService {

    /** What an export is called: {@code project-1-comparison.xlsx}. */
    private static final String FILENAME_PATTERN = "%s-comparison.%s";

    private final ComparisonService comparisons;

    ComparisonExportService(ComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    /**
     * Exports the matrix the same request would render.
     *
     * @param projectId  the project
     * @param ownerId    the calling researcher
     * @param networkIds the columns, in order; empty compares every network in the project
     * @param scenarioId pin every column to one scenario, or null
     * @param format     workbook or zipped CSV set
     */
    @Transactional(readOnly = true)
    public TabularExport.File export(long projectId, long ownerId, List<Long> networkIds,
            Long scenarioId, TabularFormat format) {
        return write(projectId,
                comparisons.compare(projectId, ownerId, networkIds, scenarioId), format);
    }

    /** The run-keyed matrix of FR-17, laid flat by exactly the same writer. */
    @Transactional(readOnly = true)
    public TabularExport.File exportRuns(long projectId, long ownerId, List<Long> runIds,
            TabularFormat format) {
        return write(projectId, comparisons.compareRuns(projectId, ownerId, runIds), format);
    }

    private static TabularExport.File write(long projectId, ComparisonMatrixDto matrix,
            TabularFormat format) {
        List<Table> tables = List.of(
                comparisonTable(matrix),
                variantsTable(matrix),
                notesTable(matrix));

        byte[] content = switch (format) {
            case XLSX -> TabularExport.workbook(tables);
            case CSV -> TabularExport.zippedCsv(tables);
        };
        return new TabularExport.File(
                FILENAME_PATTERN.formatted("project-" + projectId, format.extension()),
                format.contentType(), content);
    }

    // -------------------------------------------------------------------- the matrix

    /**
     * The matrix, one row per metric and one column per variant.
     *
     * <p>Each variant gets three columns — value, CI low, CI high — rather than one holding
     * {@code "0.82 [0.79 – 0.85]"}. A spreadsheet's value is a number a reader will sort, chart and
     * subtract; folding the interval into it would make every cell a string and the export
     * decorative.
     *
     * <p>{@code best} names the winning variant, or the tied ones, rather than being a per-cell flag:
     * the winner is a property of the row, and a column of flags would be read down instead of
     * across. It is empty for a `NEUTRAL` row, which has no winner by design.
     */
    private static Table comparisonTable(ComparisonMatrixDto matrix) {
        List<String> headers = new ArrayList<>();
        headers.add("metric_code");
        headers.add("direction");
        headers.add("unit");
        for (ComparisonVariantDto variant : matrix.variants()) {
            String label = label(matrix, variant);
            headers.add(label);
            headers.add(label + " ci_low");
            headers.add(label + " ci_high");
        }
        headers.add("best");

        List<List<String>> rows = new ArrayList<>(matrix.rows().size());
        for (ComparisonRowDto row : matrix.rows()) {
            List<String> cells = new ArrayList<>(headers.size());
            cells.add(row.metricCode());
            cells.add(row.direction().name());
            cells.add(row.unit() == null ? "" : row.unit().name());

            List<String> winners = new ArrayList<>();
            for (int i = 0; i < matrix.variants().size(); i++) {
                ComparisonCellDto cell = i < row.cells().size() ? row.cells().get(i) : null;
                if (cell == null) {
                    // Three empties, not three zeros: the variant was not measured on this metric,
                    // and a zero would read as a result.
                    cells.add("");
                    cells.add("");
                    cells.add("");
                    continue;
                }
                cells.add(TabularExport.number(cell.value()));
                cells.add(TabularExport.number(cell.ciLow()));
                cells.add(TabularExport.number(cell.ciHigh()));
                if (cell.best()) {
                    winners.add(label(matrix, matrix.variants().get(i)));
                }
            }
            cells.add(String.join(", ", winners));
            rows.add(cells);
        }
        return new Table("comparison", headers, rows);
    }

    // ------------------------------------------------------------------- the columns

    /**
     * The column legend: which configuration each column is, and what was changed to make it.
     *
     * <p>{@code lever_changes} is written as its JSON text. It is free-form by design (the
     * persistence layer stays agnostic of the lever vocabulary), so there is no set of columns to
     * flatten it into that would not go stale the first time Phase 2 adds a lever family.
     */
    private static Table variantsTable(ComparisonMatrixDto matrix) {
        List<String> headers = List.of(
                "network_id", "name", "version", "baseline", "base_network_id", "generated_by",
                "run_id", "scenario_id", "scenario_name", "run_finished_at",
                "period_length_value", "period_length_unit", "horizon_periods", "lever_changes");

        List<List<String>> rows = new ArrayList<>(matrix.variants().size());
        for (ComparisonVariantDto variant : matrix.variants()) {
            rows.add(List.of(
                    TabularExport.text(variant.networkId()),
                    variant.name(),
                    String.valueOf(variant.version()),
                    String.valueOf(variant.baseline()),
                    TabularExport.text(variant.baseNetworkId()),
                    variant.generatedBy() == null ? "" : variant.generatedBy().name(),
                    TabularExport.text(variant.runId()),
                    TabularExport.text(variant.scenarioId()),
                    TabularExport.text(variant.scenarioName()),
                    TabularExport.instant(variant.runFinishedAt()),
                    TabularExport.number(variant.periodLength().value()),
                    variant.periodLength().unit().name(),
                    String.valueOf(variant.horizonPeriods()),
                    variant.leverChanges() == null ? "" : variant.leverChanges().toString()));
        }
        return new Table("variants", headers, rows);
    }

    /** The caveats, so a file that outlives the screen it was exported from keeps them. */
    private static Table notesTable(ComparisonMatrixDto matrix) {
        List<List<String>> rows = new ArrayList<>(matrix.notes().size());
        for (ComparisonNoteDto note : matrix.notes()) {
            rows.add(List.of(note.code(), note.message()));
        }
        return new Table("notes", List.of("code", "message"), rows);
    }

    /**
     * {@code Baseline v2} — how a column is named in a header and in the {@code best} cell.
     *
     * <p>The run-keyed matrix of FR-17 can seat two runs of <em>one</em> network side by side —
     * baseline versus disruption is its whole point — and two columns named identically would make
     * the spreadsheet unreadable and its {@code best} cell ambiguous. When the matrix holds another
     * column of the same network, the label grows the run's scenario (or "baseline") and its run id;
     * a matrix of distinct networks keeps the short form every existing export has.
     */
    private static String label(ComparisonMatrixDto matrix, ComparisonVariantDto variant) {
        String base = "%s v%d".formatted(variant.name(), variant.version());
        boolean shared = matrix.variants().stream()
                .filter(other -> other.networkId().equals(variant.networkId()))
                .count() > 1;
        if (!shared) {
            return base;
        }
        String scenario = variant.scenarioName() == null ? "baseline" : variant.scenarioName();
        return "%s — %s (run %s)".formatted(base, scenario, variant.runId());
    }
}
