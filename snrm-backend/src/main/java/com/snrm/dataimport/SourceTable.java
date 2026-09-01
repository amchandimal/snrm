package com.snrm.dataimport;

import java.util.List;

/**
 * One parsed table, whichever form it arrived in: a CSV file or a worksheet.
 *
 * <p>The two adapters produce this same shape, so everything downstream — mapping, validation,
 * staging — is written once and has no idea whether it is looking at Excel or CSV. That is the point
 * of the {@link DataSourceAdapter} SPI: a third format is a new adapter and nothing else.
 *
 * <p>Held in memory rather than streamed further. The parse itself is streaming
 * ({@link XlsxAdapter} obliges), but the two-stage validation cannot be: stage 2 asks whether a
 * customer has an inbound path, which is a question about the whole graph. At the 1,000-node scale of
 * FR-04 the whole import is a few megabytes of strings, so the streaming read buys bounded parse
 * memory rather than a bounded working set.
 *
 * <p>Cells are raw strings, un-coerced. Interpreting them is stage 1's job, and it needs the original
 * text to put in a diagnostic: "{@code failure_prob} was {@code 1.4}" is actionable where "could not
 * read row 7" is not.
 *
 * @param sheet      the canonical sheet this is, resolved from the file or worksheet name
 * @param sourceName the name as it arrived — {@code nodes.csv}, or the worksheet's name
 * @param origin     how it was read, for the preview: {@code CSV (delimiter ';')} or {@code XLSX}
 * @param headers    the header row exactly as found, in file order
 * @param rows       the data rows, each carrying the line number the user sees
 */
public record SourceTable(ImportSheet sheet, String sourceName, String origin,
        List<String> headers, List<SourceRow> rows) {

    public SourceTable {
        headers = headers == null ? List.of() : List.copyOf(headers);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** How many data rows the sheet held, header excluded. */
    public int rowCount() {
        return rows.size();
    }

    /** The first {@code limit} rows, for the wizard's mapping preview. */
    public List<SourceRow> sample(int limit) {
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    /**
     * One data row.
     *
     * @param lineNumber the 1-based line as the user sees it in the file or spreadsheet — the header
     *                   is line 1, so the first data row is line 2. This is what a diagnostic quotes,
     *                   and it must survive blank rows being skipped, which is why it is carried
     *                   rather than derived from the row's index in {@link #rows()}.
     * @param cells      the row's cells in header order; short rows are padded so a missing trailing
     *                   cell reads as empty rather than as an index out of bounds
     */
    public record SourceRow(int lineNumber, List<String> cells) {

        public SourceRow {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }

        /** The cell at {@code index}, or empty string when the row was short. Never null. */
        public String cell(int index) {
            if (index < 0 || index >= cells.size()) {
                return "";
            }
            String value = cells.get(index);
            return value == null ? "" : value;
        }

        /** True when every cell is blank — a spacer row, which is skipped rather than reported. */
        public boolean isBlank() {
            for (String cell : cells) {
                if (cell != null && !cell.isBlank()) {
                    return false;
                }
            }
            return true;
        }
    }
}
