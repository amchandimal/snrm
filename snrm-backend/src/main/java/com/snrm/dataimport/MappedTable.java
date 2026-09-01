package com.snrm.dataimport;

import com.snrm.dataimport.ImportSchema.ColumnSpec;
import com.snrm.dataimport.SourceTable.SourceRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed sheet with its columns resolved to canonical names — the input stage 1 validates.
 *
 * <p>Sits between {@link SourceTable}, which knows only what the file said, and the validator, which
 * wants to ask for {@code failure_prob} without caring that the column is headed "Failure Prob." and
 * sits third from the left. Resolution is: {@link ImportSchema#suggestMapping} for the headers that are
 * already canonical, then the user's overrides from the wizard on top.
 *
 * <p>The three lists of problems found while resolving are carried rather than reported here, because
 * this type is also built for the preview endpoint, where nothing is being validated yet and
 * {@link #missingRequired()} is a status to show rather than an error to raise.
 *
 * @param table           the sheet as parsed
 * @param indexByColumn   canonical column → index into a row's cells
 * @param effectiveMapping source header → canonical column (null value = ignored), in file order, for
 *                        the wizard to render step 2 against
 * @param unmappedHeaders source headers feeding no canonical column; ignored, and reported as warnings
 * @param ambiguous       canonical columns two source headers both claimed; the first one wins
 * @param missingRequired required columns of the sheet that no source header feeds
 */
public record MappedTable(SourceTable table, Map<String, Integer> indexByColumn,
        Map<String, String> effectiveMapping, List<String> unmappedHeaders, List<String> ambiguous,
        List<String> missingRequired) {

    public MappedTable {
        indexByColumn = Map.copyOf(indexByColumn);
        effectiveMapping = new LinkedHashMap<>(effectiveMapping);
        unmappedHeaders = List.copyOf(unmappedHeaders);
        ambiguous = List.copyOf(ambiguous);
        missingRequired = List.copyOf(missingRequired);
    }

    /**
     * Resolves one sheet's headers against the schema and the user's overrides.
     *
     * <p>A user override wins over the automatic suggestion always, including when it says "ignore"
     * (a null or blank canonical name). The alternative — treating a null as "no opinion, use the
     * suggestion" — would make it impossible to drop a column the wizard had matched, and there is a
     * real case for that: a file with both {@code lat} and a stale {@code lat_old} column.
     *
     * @param userMapping source header → canonical column, from {@link ImportMapping#forSheet}
     */
    public static MappedTable of(SourceTable table, Map<String, String> userMapping) {
        ImportSheet sheet = table.sheet();
        Map<String, String> effective =
                new LinkedHashMap<>(ImportSchema.suggestMapping(sheet, table.headers()));

        for (Map.Entry<String, String> override : userMapping.entrySet()) {
            String header = matchingHeader(table.headers(), override.getKey());
            if (header == null) {
                continue; // an override for a column this file does not have; harmless, ignored
            }
            String canonical = override.getValue();
            boolean known = canonical != null && !canonical.isBlank()
                    && ImportSchema.columnOf(sheet, canonical).isPresent();
            effective.put(header, known ? canonical : null);
        }

        Map<String, Integer> indexByColumn = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();

        List<String> headers = table.headers();
        for (int index = 0; index < headers.size(); index++) {
            String canonical = effective.get(headers.get(index));
            if (canonical == null) {
                unmapped.add(headers.get(index));
                continue;
            }
            if (indexByColumn.putIfAbsent(canonical, index) != null) {
                ambiguous.add(canonical);
            }
        }

        List<String> missingRequired = new ArrayList<>();
        for (ColumnSpec column : ImportSchema.columnsOf(sheet)) {
            if (column.required() && !indexByColumn.containsKey(column.name())) {
                missingRequired.add(column.name());
            }
        }

        return new MappedTable(table, indexByColumn, effective, unmapped, ambiguous, missingRequired);
    }

    public ImportSheet sheet() {
        return table.sheet();
    }

    public List<SourceRow> rows() {
        return table.rows();
    }

    /** True when a canonical column has a source column behind it. */
    public boolean has(String column) {
        return indexByColumn.containsKey(column);
    }

    /**
     * The cell for a canonical column, trimmed; empty string when the column is absent or the row is
     * short.
     *
     * <p>Empty and absent are deliberately the same answer. Both mean "the file said nothing here", and
     * every default — unconstrained capacity, zero cost, the period unit for a missing unit
     * column — applies identically to a column that was never provided and a cell that was left blank.
     * The one place the distinction matters is a <em>required</em> column, and that is caught before any
     * row is read, by {@link #missingRequired()}.
     */
    public String value(SourceRow row, String column) {
        Integer index = indexByColumn.get(column);
        return index == null ? "" : row.cell(index).trim();
    }

    /** The source header a canonical column was fed from, for a diagnostic that has to name it. */
    public String sourceHeaderOf(String column) {
        Integer index = indexByColumn.get(column);
        return index == null || index >= table.headers().size() ? column : table.headers().get(index);
    }

    /** A row wider than the header, whose surplus cells are ignored. */
    public boolean isWiderThanHeader(SourceRow row) {
        return row.cells().size() > table.headers().size();
    }

    /**
     * The file's header matching an override key, comparing the way headers are compared everywhere
     * else ({@link ImportSchema#normaliseHeader}).
     *
     * <p>So a wizard that echoes back {@code "Lead Time"} where the file holds {@code "lead time"}
     * still lands on the right column — the client is not required to preserve the header byte for
     * byte, only to identify it.
     */
    private static String matchingHeader(List<String> headers, String key) {
        String normalisedKey = ImportSchema.normaliseHeader(key);
        for (String header : headers) {
            if (ImportSchema.normaliseHeader(header).equals(normalisedKey)) {
                return header;
            }
        }
        return null;
    }
}
