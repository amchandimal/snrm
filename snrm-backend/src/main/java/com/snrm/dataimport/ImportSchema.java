package com.snrm.dataimport;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The canonical import/export schema, as data rather than as five parsers.
 *
 * <p><strong>Why one table drives everything.</strong> Four things have to agree about what a column
 * is called and what may be in it: the mapping step of the wizard, the row-level validation
 * stage 1, the export writer, and the documentation. Where those four are written
 * separately they drift, and the drift shows up as an export that cannot be re-imported — which is
 * precisely the round trip that is a supported workflow. So the columns are declared once,
 * here, and the writer emits {@link #columnsOf} in order while the reader validates against the same
 * list. A round trip is then structural rather than a promise someone has to keep.
 *
 * <p><strong>Units are columns, not syntax.</strong> Every unit-bearing field is a pair: a value
 * column and an optional {@code *_unit} column ({@link ColumnSpec#unitOwner()}). Omit the unit column
 * and the value is read in the network's period unit, so a file written before units existed imports
 * unchanged. Export always writes both halves, which is what makes the round trip
 * unit-preserving.
 *
 * <p>Header matching is case-insensitive and punctuation-tolerant ({@link #normaliseHeader}); a
 * header that still does not match is offered to the user in the mapping step rather than rejected,
 * because renaming a column is the one import problem a wizard can fix without touching the file.
 */
public final class ImportSchema {

    /** What kind of value a column holds, and therefore what stage 1 checks about it. */
    public enum ColumnKind {

        /** Free text. Length is checked against the column it lands in. */
        TEXT,

        /** A real number, possibly fractional and possibly negative where the field allows it. */
        NUMBER,

        /** A real number that must not be negative — a cost, a capacity, a quantity. */
        NON_NEGATIVE,

        /** A real number in [0,1] — {@code failure_prob} ({@code ck_*_failure_prob}). */
        PROBABILITY,

        /** A whole number ≥ 1 — {@code horizon_periods} ({@code ck_network_horizon}). */
        POSITIVE_INTEGER,

        /** A {@code NodeType} literal. */
        NODE_TYPE,

        /** A {@code RoundingPolicy} literal. */
        ROUNDING_POLICY,

        /** A time-unit token, case-insensitive and abbreviated ({@link UnitTokens}). */
        TIME_UNIT,

        /**
         * A yes/no token — {@code caption_visible} (FR-30). Read through
         * {@link UnitTokens#flag(String)}, which is lenient about spelling and case and refuses
         * anything it is not sure of. An empty cell is <em>absent</em>, and an absent flag is
         * VISIBLE.
         */
        FLAG,

        /** A latitude or longitude; range-checked, and legitimately negative. */
        LATITUDE,

        /** @see #LATITUDE */
        LONGITUDE
    }

    /**
     * One canonical column.
     *
     * @param name      the canonical header, exactly as a file must spell it
     * @param required  an absent column, or an empty cell, is an error
     * @param kind      what stage 1 checks
     * @param unitOwner for a {@code *_unit} column, the value column it qualifies; null otherwise.
     *                  Import falls back to the network's period unit when a unit column is missing,
     *                  and export always writes it.
     * @param note      one line for the wizard's mapping step and the OpenAPI description
     */
    public record ColumnSpec(String name, boolean required, ColumnKind kind, String unitOwner,
            String note) {

        static ColumnSpec required(String name, ColumnKind kind, String note) {
            return new ColumnSpec(name, true, kind, null, note);
        }

        static ColumnSpec optional(String name, ColumnKind kind, String note) {
            return new ColumnSpec(name, false, kind, null, note);
        }

        /** The unit half of a value column — always optional, by the rule. */
        static ColumnSpec unit(String name, String owner) {
            return new ColumnSpec(name, false, ColumnKind.TIME_UNIT, owner,
                    "Unit of " + owner + ". Omit to read the value in the network's period unit.");
        }

        /** True for a {@code *_unit} column. */
        public boolean isUnit() {
            return unitOwner != null;
        }
    }

    // ------------------------------------------------------------------ the columns

    private static final Map<ImportSheet, List<ColumnSpec>> COLUMNS = columns();

    private static Map<ImportSheet, List<ColumnSpec>> columns() {
        Map<ImportSheet, List<ColumnSpec>> map = new EnumMap<>(ImportSheet.class);

        map.put(ImportSheet.NETWORK_META, List.of(
                ColumnSpec.required("period_length_value", ColumnKind.NON_NEGATIVE,
                        "Length of one simulation period; must be greater than zero."),
                ColumnSpec.required("period_length_unit", ColumnKind.TIME_UNIT,
                        "Unit the period length is stated in."),
                ColumnSpec.required("horizon_periods", ColumnKind.POSITIVE_INTEGER,
                        "How many periods a run over this network covers."),
                ColumnSpec.optional("rounding_policy", ColumnKind.ROUNDING_POLICY,
                        "NEAREST | UP | DOWN. Defaults to NEAREST."),
                ColumnSpec.optional("name", ColumnKind.TEXT,
                        "The network's own name. The import request overrides it; a file that "
                                + "carries one can be imported without naming it again.")));

        map.put(ImportSheet.NODES, List.of(
                ColumnSpec.required("name", ColumnKind.TEXT,
                        "Unique within the file; links resolve their endpoints by it."),
                ColumnSpec.required("type", ColumnKind.NODE_TYPE,
                        "SUPPLIER | PLANT | DC | CUSTOMER."),
                ColumnSpec.optional("capacity_value", ColumnKind.NON_NEGATIVE,
                        "Throughput ceiling as a rate. Empty means unconstrained."),
                ColumnSpec.unit("capacity_time_unit", "capacity_value"),
                ColumnSpec.optional("processing_time_value", ColumnKind.NON_NEGATIVE,
                        "Dwell before material can leave. Defaults to 0."),
                ColumnSpec.unit("processing_time_unit", "processing_time_value"),
                ColumnSpec.optional("fixed_cost", ColumnKind.NON_NEGATIVE, "Defaults to 0."),
                ColumnSpec.optional("var_cost", ColumnKind.NON_NEGATIVE, "Defaults to 0."),
                ColumnSpec.optional("failure_prob", ColumnKind.PROBABILITY,
                        "Per-period failure probability in [0,1]. Defaults to 0."),
                ColumnSpec.optional("region", ColumnKind.TEXT,
                        "Tag REGION-scoped disruptions resolve through."),
                ColumnSpec.optional("lat", ColumnKind.LATITUDE, "Geographic latitude, −90 to 90."),
                ColumnSpec.optional("lng", ColumnKind.LONGITUDE,
                        "Geographic longitude, −180 to 180."),
                ColumnSpec.optional("pos_x", ColumnKind.NUMBER,
                        "Editor canvas x-coordinate — layout, not geography. Omitted, the "
                                + "editor auto-lays out the imported network."),
                ColumnSpec.optional("pos_y", ColumnKind.NUMBER,
                        "Editor canvas y-coordinate. Omitted, the editor auto-lays out."),
                ColumnSpec.optional("caption", ColumnKind.TEXT,
                        "Short annotation drawn under the node's name on the canvas (FR-30). "
                                + "At most 200 characters; empty means none."),
                ColumnSpec.optional("caption_visible", ColumnKind.FLAG,
                        "Whether the canvas draws the caption. OMITTING IT MEANS VISIBLE, so a file "
                                + "carrying only 'caption' behaves the way whoever wrote it expects. "
                                + "An empty caption draws nothing either way.")));

        map.put(ImportSheet.LINKS, List.of(
                ColumnSpec.required("source", ColumnKind.TEXT, "A nodes.name."),
                ColumnSpec.required("target", ColumnKind.TEXT, "A nodes.name; not the same one."),
                ColumnSpec.optional("lead_time_value", ColumnKind.NON_NEGATIVE,
                        "Transit time. Defaults to 0 — flow arrives within the period."),
                ColumnSpec.unit("lead_time_unit", "lead_time_value"),
                ColumnSpec.optional("capacity_value", ColumnKind.NON_NEGATIVE,
                        "Throughput ceiling as a rate. Empty means unconstrained."),
                ColumnSpec.unit("capacity_time_unit", "capacity_value"),
                ColumnSpec.optional("unit_cost", ColumnKind.NON_NEGATIVE, "Defaults to 0."),
                ColumnSpec.optional("failure_prob", ColumnKind.PROBABILITY,
                        "Per-period failure probability in [0,1]. Defaults to 0."),
                ColumnSpec.optional("caption", ColumnKind.TEXT,
                        "Short annotation drawn under the arc's lead-time label (FR-30). "
                                + "At most 200 characters; empty means none."),
                ColumnSpec.optional("caption_visible", ColumnKind.FLAG,
                        "Whether the canvas draws the caption; omitting it means VISIBLE, as for "
                                + "nodes.")));

        map.put(ImportSheet.PRODUCTS, List.of(
                ColumnSpec.required("name", ColumnKind.TEXT, "Unique within the project."),
                ColumnSpec.optional("unit_value", ColumnKind.NON_NEGATIVE,
                        "Value of one unit; weights unserved demand. Defaults to 0.")));

        map.put(ImportSheet.NODE_PRODUCTS, List.of(
                ColumnSpec.required("node", ColumnKind.TEXT, "A nodes.name."),
                ColumnSpec.required("product", ColumnKind.TEXT,
                        "A products.name, or a product already in the project."),
                ColumnSpec.optional("demand_value", ColumnKind.NON_NEGATIVE,
                        "Demand as a rate; applies to CUSTOMER nodes. Defaults to 0."),
                ColumnSpec.unit("demand_time_unit", "demand_value"),
                ColumnSpec.optional("initial_inventory", ColumnKind.NON_NEGATIVE,
                        "Opening stock — a level, so it carries no unit. Defaults to 0."),
                ColumnSpec.optional("safety_stock", ColumnKind.NON_NEGATIVE, "Defaults to 0."),
                ColumnSpec.optional("holding_cost_value", ColumnKind.NON_NEGATIVE,
                        "Cost of carrying one unit, as a rate. Defaults to 0."),
                ColumnSpec.unit("holding_cost_time_unit", "holding_cost_value")));

        return map;
    }

    private ImportSchema() {
    }

    /** Every column of a sheet, in the order export writes them. */
    public static List<ColumnSpec> columnsOf(ImportSheet sheet) {
        return COLUMNS.get(sheet);
    }

    /** Just the canonical header names of a sheet, in schema order. */
    public static List<String> headersOf(ImportSheet sheet) {
        List<String> headers = new ArrayList<>();
        for (ColumnSpec column : columnsOf(sheet)) {
            headers.add(column.name());
        }
        return headers;
    }

    /** The named column of a sheet, matched exactly. */
    public static Optional<ColumnSpec> columnOf(ImportSheet sheet, String canonicalName) {
        for (ColumnSpec column : columnsOf(sheet)) {
            if (column.name().equals(canonicalName)) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }

    /** The required columns of a sheet — those a file is invalid without. */
    public static List<String> requiredColumnsOf(ImportSheet sheet) {
        List<String> required = new ArrayList<>();
        for (ColumnSpec column : columnsOf(sheet)) {
            if (column.required()) {
                required.add(column.name());
            }
        }
        return required;
    }

    /**
     * {@code "Lead Time Value "} → {@code "lead_time_value"} — how a source header is compared
     * against a canonical one.
     *
     * <p>Case, surrounding whitespace, and the choice between space, hyphen and underscore are all
     * noise a spreadsheet introduces without the user intending anything by it, so they are folded
     * away. Anything beyond that is a real difference and goes to the mapping step.
     */
    public static String normaliseHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("_+", "_");
    }

    /**
     * The canonical column a source header maps to on its own, before the user says anything.
     *
     * <p>Exact match after normalisation only. There is no fuzzy matching here on purpose: a wrong
     * guess in the mapping step is worse than no guess, because the user confirms the step rather
     * than re-deriving it, and {@code lead_time} silently becoming {@code lead_time_value} while the
     * unit column goes unmapped is exactly the kind of near-miss that would import quietly and mean
     * something else. Unmatched headers arrive at the wizard unmapped, which is visible.
     */
    public static Optional<String> suggestColumn(ImportSheet sheet, String sourceHeader) {
        String normalised = normaliseHeader(sourceHeader);
        for (ColumnSpec column : columnsOf(sheet)) {
            if (column.name().equals(normalised)) {
                return Optional.of(column.name());
            }
        }
        return Optional.empty();
    }

    /**
     * The whole auto-mapping for one sheet's headers: source header → canonical column, or null for
     * a header nothing matches.
     *
     * <p>Insertion-ordered so the wizard lists the columns in the order they appear in the file,
     * which is the order the user is reading them in.
     */
    public static Map<String, String> suggestMapping(ImportSheet sheet, List<String> sourceHeaders) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String header : sourceHeaders) {
            String canonical = suggestColumn(sheet, header).orElse(null);
            // A canonical column claimed twice — two source columns both called "name" — is left to
            // the first one; stage 1 reports the duplicate rather than silently preferring the last.
            if (canonical != null && mapping.containsValue(canonical)) {
                canonical = null;
            }
            mapping.put(header, canonical);
        }
        return mapping;
    }
}
