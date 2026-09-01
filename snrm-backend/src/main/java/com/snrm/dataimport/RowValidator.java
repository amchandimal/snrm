package com.snrm.dataimport;

import com.snrm.common.DurationAmount;
import com.snrm.common.Rate;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import com.snrm.dataimport.ImportSchema.ColumnSpec;
import com.snrm.dataimport.SourceTable.SourceRow;
import com.snrm.dataimport.StagedNetwork.StagedLink;
import com.snrm.dataimport.StagedNetwork.StagedNode;
import com.snrm.dataimport.StagedNetwork.StagedNodeProduct;
import com.snrm.dataimport.StagedNetwork.StagedProduct;
import com.snrm.dataimport.StagedNetwork.TimeBaseSpec;
import com.snrm.network.NodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 1: every check that can be made about one row, reported against its line.
 *
 * <blockquote>"types, ranges (e.g. {@code failure_prob ∈ [0,1]}), unit-token recognition, uniqueness and
 * referential integrity, reported as a per-row error table with line numbers so the user can fix the
 * source file or correct values inline in the wizard."</blockquote>
 *
 * <p><strong>Reports everything it can, then stages what it can.</strong> A row with a bad cell is
 * reported and dropped; the sheet keeps going. Aborting on the first error would make the wizard a
 * fix-one-recompile loop through a fifty-node file, and the whole reason the report is a table with line
 * numbers is that a researcher fixes a spreadsheet in one pass. Dropping the row rather than staging it
 * half-read is what keeps stage 2 honest: a node with an unreadable type cannot take part in an echelon
 * check, and guessing a type for it would invent a finding.
 *
 * <p><strong>What "dropped" costs.</strong> The resulting {@link StagedNetwork#complete()} is false,
 * which the caller turns into a single warning saying that the network-level findings describe a partial
 * graph. Stage 2 still runs — a researcher wants to see the structural problems and the cell
 * problems in one pass — but nothing is written while an error stands, so a provisional stage-2 finding
 * can only ever mislead about a network that was never created.
 *
 * <p><strong>Units.</strong> A missing {@code *_unit} column, or an empty unit cell, means the value is
 * in the network's period unit — so a plain numeric file behaves exactly as it did before
 * units existed. A unit token that is <em>present but unreadable</em> is an error rather than a fallback:
 * the user did state a unit, and quietly substituting another would rescale their number.
 */
@Component
public class RowValidator {

    /** {@code node.name}, {@code product.name} — {@code VARCHAR(120)} in V2. */
    private static final int NAME_LENGTH = 120;

    /** {@code node.region} — {@code VARCHAR(60)}. */
    private static final int REGION_LENGTH = 60;

    /** {@code node.caption}, {@code link.caption} — {@code VARCHAR(200)} in V10 (FR-30). */
    private static final int CAPTION_LENGTH = 200;

    /**
     * Joins the two halves of a composite key — {@code (source, target)}, {@code (node, product)}.
     *
     * <p>A character that cannot occur in a spreadsheet cell, so no pair of names can collide into one
     * key and be reported as a duplicate that is not one. Any printable separator could: a node
     * legitimately called {@code "A|B"} would collide with the pair {@code (A, B)}.
     */
    private static final String KEY_SEPARATOR = "\u0000";

    /**
     * Reads and checks every sheet, in the dependency order of {@link ImportSheet}.
     *
     * @param tables          the mapped sheets present in the upload
     * @param override        the time base the wizard confirmed, or null to take the file's
     * @param existingProducts product names already in the project, which {@code node_products} may
     *                        legitimately reference without the {@code products} sheet repeating them
     *                        (products are project-scoped)
     * @param diagnostics     collector for every finding
     */
    public StagedNetwork stage(Map<ImportSheet, MappedTable> tables, TimeBaseSpec override,
            Set<String> existingProducts, Diagnostics diagnostics) {

        MappedTable meta = tables.get(ImportSheet.NETWORK_META);
        if (meta != null) {
            reportColumnProblems(meta, diagnostics);
        }
        TimeBaseSpec timeBase = timeBaseOf(meta, override, diagnostics);
        String declaredName = declaredNameOf(meta);
        TimeUnit fallbackUnit = timeBase.periodUnit();

        Tally tally = new Tally();
        List<StagedNode> nodes = nodes(tables.get(ImportSheet.NODES), fallbackUnit, diagnostics, tally);
        List<StagedProduct> products =
                products(tables.get(ImportSheet.PRODUCTS), diagnostics, tally);

        Set<String> nodeNames = new LinkedHashSet<>();
        for (StagedNode node : nodes) {
            nodeNames.add(key(node.name()));
        }
        Set<String> productNames = new LinkedHashSet<>(existingProducts.size() + products.size());
        for (String existing : existingProducts) {
            productNames.add(key(existing));
        }
        for (StagedProduct product : products) {
            productNames.add(key(product.name()));
        }

        List<StagedLink> links = links(tables.get(ImportSheet.LINKS), nodeNames, fallbackUnit,
                diagnostics, tally);
        List<StagedNodeProduct> nodeProducts = nodeProducts(tables.get(ImportSheet.NODE_PRODUCTS),
                nodeNames, productNames, tables.containsKey(ImportSheet.PRODUCTS), fallbackUnit,
                diagnostics, tally);

        return new StagedNetwork(timeBase, declaredName, nodes, links, products, nodeProducts,
                !tally.droppedAny());
    }

    /**
     * The network's own name from {@code network_meta}, or null.
     *
     * <p>Read without validation and never reported on: it is optional everywhere, the import request
     * overrides it, and a name that is too long for the column is caught by the same check every other
     * network name goes through when it is written.
     */
    static String declaredNameOf(MappedTable meta) {
        if (meta == null || meta.rows().isEmpty()) {
            return null;
        }
        String name = meta.value(meta.rows().get(0), "name");
        return name.isEmpty() ? null : name;
    }

    // ------------------------------------------------------------------ network_meta

    /**
     * The network's clock, from three possible sources in this order of authority: the
     * wizard's confirmed override, the {@code network_meta} sheet, then the documented defaults.
     *
     * <p>The wizard wins because it has to <em>ask</em> when the sheet is absent, and an
     * answer that could be overruled by the file would make the question dishonest. When the sheet is
     * absent and nothing was confirmed, the defaults apply with a warning — never silently, because the
     * period is what every duration in the file is interpreted against.
     */
    public TimeBaseSpec timeBaseOf(MappedTable meta, TimeBaseSpec override, Diagnostics diagnostics) {
        if (override != null) {
            return override;
        }
        if (meta == null) {
            diagnostics.add(ImportDiagnostic.sheet(ImportSheet.NETWORK_META, null,
                    ImportSeverity.WARNING, ImportCheck.SHEET_DEFAULTED,
                    "No network_meta sheet: assuming a period of 1 DAY, a horizon of 52 periods and "
                            + "NEAREST rounding. Every duration in this import is "
                            + "interpreted against that period, and every value in a column without "
                            + "a unit is read as days — confirm it before importing."));
            return TimeBaseSpec.defaults();
        }
        if (meta.rows().isEmpty()) {
            diagnostics.add(ImportDiagnostic.sheet(ImportSheet.NETWORK_META, null,
                    ImportSeverity.WARNING, ImportCheck.SHEET_DEFAULTED,
                    "network_meta has a header but no rows; the defaults apply."));
            return TimeBaseSpec.defaults();
        }
        if (meta.rows().size() > 1) {
            diagnostics.add(ImportDiagnostic.row(ImportSheet.NETWORK_META,
                    meta.rows().get(1).lineNumber(), ImportSeverity.WARNING,
                    ImportCheck.ROW_WIDTH_MISMATCH, null,
                    ("network_meta describes one network, so only the first row is read; %d further "
                            + "row(s) are ignored.").formatted(meta.rows().size() - 1)));
        }

        SourceRow row = meta.rows().get(0);
        Cells cells = new Cells(meta, row, diagnostics);
        TimeBaseSpec defaults = TimeBaseSpec.defaults();

        double periodValue = cells.positiveNumber("period_length_value")
                .orElse(defaults.periodLength().getValue());
        TimeUnit periodUnit = cells.timeUnit("period_length_unit").orElse(defaults.periodUnit());
        int horizon = cells.positiveInteger("horizon_periods").orElse(defaults.horizonPeriods());
        RoundingPolicy policy = cells.roundingPolicy("rounding_policy")
                .orElse(defaults.roundingPolicy());

        DurationAmount period = DurationAmount.of(periodValue, periodUnit);
        if (period.getSeconds() < 1) {
            // Every conversion divides by the period, and the canonical form is whole
            // seconds, so a period under half a second is not a network anyone can run.
            // ck_network_period would reject it at the last moment; saying so here names the cell.
            diagnostics.error(ImportSheet.NETWORK_META, row.lineNumber(), "period_length_value",
                    ImportCheck.OUT_OF_RANGE, String.valueOf(periodValue),
                    ("A period of %s %s is under one second, which is the finest the canonical form "
                            + "resolves. Every duration in the network would be divided "
                            + "by it.").formatted(periodValue, periodUnit));
            return defaults;
        }
        return new TimeBaseSpec(period, horizon, policy, true);
    }

    // ------------------------------------------------------------------------- nodes

    private List<StagedNode> nodes(MappedTable table, TimeUnit fallbackUnit,
            Diagnostics diagnostics, Tally tally) {
        List<StagedNode> staged = new ArrayList<>();
        if (table == null) {
            return staged;
        }
        reportColumnProblems(table, diagnostics);
        Map<String, Integer> firstLineByName = new HashMap<>();

        for (SourceRow row : table.rows()) {
            reportRowWidth(table, row, diagnostics);
            Cells cells = new Cells(table, row, diagnostics);

            Optional<String> name = cells.requiredText("name", NAME_LENGTH);
            Optional<NodeType> type = cells.nodeType("type");
            if (name.isEmpty() || type.isEmpty()) {
                tally.dropped();
                continue;
            }
            Integer firstLine = firstLineByName.putIfAbsent(key(name.get()), row.lineNumber());
            if (firstLine != null) {
                diagnostics.add(ImportDiagnostic.row(ImportSheet.NODES, row.lineNumber(),
                        ImportSeverity.ERROR, ImportCheck.DUPLICATE_KEY, name.get(),
                        ("Node name '%s' is already used on line %d. Names are unique within a "
                                + "network — links resolve their endpoints by them.")
                                .formatted(name.get(), firstLine)));
                tally.dropped();
                continue;
            }

            Rate capacity = cells.rate("capacity_value", "capacity_time_unit", fallbackUnit, null);
            DurationAmount processingTime =
                    cells.duration("processing_time_value", "processing_time_unit", fallbackUnit);
            double fixedCost = cells.nonNegative("fixed_cost").orElse(0d);
            double varCost = cells.nonNegative("var_cost").orElse(0d);
            double failureProb = cells.probability("failure_prob").orElse(0d);
            String region = cells.text("region", REGION_LENGTH).orElse(null);
            Double lat = cells.bounded("lat", -90, 90).orElse(null);
            Double lng = cells.bounded("lng", -180, 180).orElse(null);
            // Canvas coordinates are unbounded and legitimately negative: they are whatever the editor
            // last wrote, in its own space. Absent means "no layout", which the editor
            // answers with auto-layout — not zero, which would stack every node on the origin.
            Double posX = cells.number("pos_x").orElse(null);
            Double posY = cells.number("pos_y").orElse(null);
            // FR-30. An absent caption is null, and an absent flag is VISIBLE — not false, which
            // would make a file carrying only `caption` import an annotation nobody can see.
            String caption = cells.text("caption", CAPTION_LENGTH).orElse(null);
            boolean captionVisible = cells.flag("caption_visible").orElse(true);

            if (cells.failed()) {
                tally.dropped();
                continue;
            }
            staged.add(new StagedNode(row.lineNumber(), name.get(), type.get(), capacity,
                    processingTime, fixedCost, varCost, failureProb, region, lat, lng, posX, posY,
                    caption, captionVisible));
        }
        return staged;
    }

    // ------------------------------------------------------------------------- links

    private List<StagedLink> links(MappedTable table, Set<String> nodeNames, TimeUnit fallbackUnit,
            Diagnostics diagnostics, Tally tally) {
        List<StagedLink> staged = new ArrayList<>();
        if (table == null) {
            return staged;
        }
        reportColumnProblems(table, diagnostics);
        Map<String, Integer> firstLineByPair = new HashMap<>();

        for (SourceRow row : table.rows()) {
            reportRowWidth(table, row, diagnostics);
            Cells cells = new Cells(table, row, diagnostics);

            Optional<String> source = cells.requiredText("source", NAME_LENGTH);
            Optional<String> target = cells.requiredText("target", NAME_LENGTH);
            if (source.isEmpty() || target.isEmpty()) {
                tally.dropped();
                continue;
            }
            boolean resolved = true;
            if (!nodeNames.contains(key(source.get()))) {
                diagnostics.error(ImportSheet.LINKS, row.lineNumber(), "source",
                        ImportCheck.UNKNOWN_NODE_REFERENCE, source.get(),
                        "No node named '%s' — links resolve their endpoints against nodes.name."
                                .formatted(source.get()));
                resolved = false;
            }
            if (!nodeNames.contains(key(target.get()))) {
                diagnostics.error(ImportSheet.LINKS, row.lineNumber(), "target",
                        ImportCheck.UNKNOWN_NODE_REFERENCE, target.get(),
                        "No node named '%s' — links resolve their endpoints against nodes.name."
                                .formatted(target.get()));
                resolved = false;
            }
            if (key(source.get()).equals(key(target.get()))) {
                diagnostics.add(ImportDiagnostic.row(ImportSheet.LINKS, row.lineNumber(),
                        ImportSeverity.ERROR, ImportCheck.SELF_LOOP, source.get(),
                        ("A link from '%s' to itself is not a transport arc; the schema forbids it "
                                + "(ck_link_no_self_loop).").formatted(source.get())));
                resolved = false;
            }
            String pair = key(source.get()) + KEY_SEPARATOR + key(target.get());
            Integer firstLine = firstLineByPair.putIfAbsent(pair, row.lineNumber());
            if (firstLine != null) {
                diagnostics.add(ImportDiagnostic.row(ImportSheet.LINKS, row.lineNumber(),
                        ImportSeverity.ERROR, ImportCheck.DUPLICATE_KEY,
                        source.get() + " → " + target.get(),
                        ("This link is already declared on line %d. One arc per ordered pair "
                                + "(uq_link); give it one row with the attributes you want.")
                                .formatted(firstLine)));
                resolved = false;
            }

            DurationAmount leadTime =
                    cells.duration("lead_time_value", "lead_time_unit", fallbackUnit);
            Rate capacity = cells.rate("capacity_value", "capacity_time_unit", fallbackUnit, null);
            double unitCost = cells.nonNegative("unit_cost").orElse(0d);
            double failureProb = cells.probability("failure_prob").orElse(0d);
            String caption = cells.text("caption", CAPTION_LENGTH).orElse(null);
            boolean captionVisible = cells.flag("caption_visible").orElse(true);

            if (!resolved || cells.failed()) {
                tally.dropped();
                continue;
            }
            staged.add(new StagedLink(row.lineNumber(), source.get(), target.get(), leadTime,
                    capacity, unitCost, failureProb, caption, captionVisible));
        }
        return staged;
    }

    // ---------------------------------------------------------------------- products

    private List<StagedProduct> products(MappedTable table, Diagnostics diagnostics, Tally tally) {
        List<StagedProduct> staged = new ArrayList<>();
        if (table == null) {
            return staged;
        }
        reportColumnProblems(table, diagnostics);
        Map<String, Integer> firstLineByName = new HashMap<>();

        for (SourceRow row : table.rows()) {
            reportRowWidth(table, row, diagnostics);
            Cells cells = new Cells(table, row, diagnostics);

            Optional<String> name = cells.requiredText("name", NAME_LENGTH);
            if (name.isEmpty()) {
                tally.dropped();
                continue;
            }
            Integer firstLine = firstLineByName.putIfAbsent(key(name.get()), row.lineNumber());
            if (firstLine != null) {
                diagnostics.add(ImportDiagnostic.row(ImportSheet.PRODUCTS, row.lineNumber(),
                        ImportSeverity.ERROR, ImportCheck.DUPLICATE_KEY, name.get(),
                        "Product '%s' is already declared on line %d (uq_product)."
                                .formatted(name.get(), firstLine)));
                tally.dropped();
                continue;
            }
            double unitValue = cells.nonNegative("unit_value").orElse(0d);
            if (cells.failed()) {
                tally.dropped();
                continue;
            }
            staged.add(new StagedProduct(row.lineNumber(), name.get(), unitValue));
        }
        return staged;
    }

    // ----------------------------------------------------------------- node_products

    /**
     * @param productsDeclared whether the upload carried a {@code products} sheet. When it did not, a
     *                         product name here is <em>created</em> rather than rejected: that sheet is
     *                         optional and a default product is created in its absence,
     *                         so the names this sheet uses are the catalogue. When it did, an
     *                         unmatched name is a typo worth reporting, because the file has said what
     *                         its products are.
     */
    private List<StagedNodeProduct> nodeProducts(MappedTable table, Set<String> nodeNames,
            Set<String> productNames, boolean productsDeclared, TimeUnit fallbackUnit,
            Diagnostics diagnostics, Tally tally) {
        List<StagedNodeProduct> staged = new ArrayList<>();
        if (table == null) {
            return staged;
        }
        reportColumnProblems(table, diagnostics);
        Map<String, Integer> firstLineByPair = new HashMap<>();

        for (SourceRow row : table.rows()) {
            reportRowWidth(table, row, diagnostics);
            Cells cells = new Cells(table, row, diagnostics);

            Optional<String> node = cells.requiredText("node", NAME_LENGTH);
            Optional<String> product = cells.requiredText("product", NAME_LENGTH);
            if (node.isEmpty() || product.isEmpty()) {
                tally.dropped();
                continue;
            }
            boolean resolved = true;
            if (!nodeNames.contains(key(node.get()))) {
                diagnostics.error(ImportSheet.NODE_PRODUCTS, row.lineNumber(), "node",
                        ImportCheck.UNKNOWN_NODE_REFERENCE, node.get(),
                        "No node named '%s' in the nodes sheet.".formatted(node.get()));
                resolved = false;
            }
            if (productsDeclared && !productNames.contains(key(product.get()))) {
                diagnostics.error(ImportSheet.NODE_PRODUCTS, row.lineNumber(), "product",
                        ImportCheck.UNKNOWN_PRODUCT_REFERENCE, product.get(),
                        ("No product named '%s', either in the products sheet or already in this "
                                + "project. Products are project-scoped, so a variant may reference "
                                + "the catalogue without repeating it.")
                                .formatted(product.get()));
                resolved = false;
            }
            String pair = key(node.get()) + KEY_SEPARATOR + key(product.get());
            Integer firstLine = firstLineByPair.putIfAbsent(pair, row.lineNumber());
            if (firstLine != null) {
                diagnostics.add(ImportDiagnostic.row(ImportSheet.NODE_PRODUCTS, row.lineNumber(),
                        ImportSeverity.ERROR, ImportCheck.DUPLICATE_KEY,
                        node.get() + " / " + product.get(),
                        ("(node, product) is the natural key of this sheet and this pair is already "
                                + "declared on line %d.").formatted(firstLine)));
                resolved = false;
            }

            Rate demand = cells.rate("demand_value", "demand_time_unit", fallbackUnit, 0d);
            double initialInventory = cells.nonNegative("initial_inventory").orElse(0d);
            double safetyStock = cells.nonNegative("safety_stock").orElse(0d);
            Rate holdingCost =
                    cells.rate("holding_cost_value", "holding_cost_time_unit", fallbackUnit, 0d);

            if (!resolved || cells.failed()) {
                tally.dropped();
                continue;
            }
            staged.add(new StagedNodeProduct(row.lineNumber(), node.get(), product.get(), demand,
                    initialInventory, safetyStock, holdingCost));
        }
        return staged;
    }

    // ----------------------------------------------------------------- sheet problems

    /**
     * The findings that are about the sheet's columns rather than any row: a required column with no
     * source, a source column feeding nothing, two columns claiming one target.
     *
     * <p>All three are fixable in the wizard's mapping step without touching the file, which is why
     * they carry the source header rather than the canonical name where the two differ — the user is
     * looking at their own column names.
     */
    public void reportColumnProblems(MappedTable table, Diagnostics diagnostics) {
        ImportSheet sheet = table.sheet();
        for (String column : table.missingRequired()) {
            diagnostics.add(ImportDiagnostic.sheet(sheet, column, ImportSeverity.ERROR,
                    ImportCheck.MISSING_REQUIRED_COLUMN,
                    ("Required column '%s' is missing from %s. Add it to the file, or map one of its "
                            + "columns onto it in the mapping step.")
                            .formatted(column, table.table().sourceName())));
        }
        for (String header : table.unmappedHeaders()) {
            diagnostics.add(ImportDiagnostic.sheet(sheet, header, ImportSeverity.WARNING,
                    ImportCheck.UNMAPPED_COLUMN,
                    ("Column '%s' matches no canonical column of %s and is ignored. Map it in the "
                            + "mapping step if it holds data you want.")
                            .formatted(header, sheet.canonicalName())));
        }
        for (String column : table.ambiguous()) {
            diagnostics.add(ImportDiagnostic.sheet(sheet, column, ImportSeverity.ERROR,
                    ImportCheck.AMBIGUOUS_COLUMN,
                    ("Two columns are mapped onto '%s'. Only the leftmost would be read, so the "
                            + "mapping has to say which one is meant.").formatted(column)));
        }
    }

    private void reportRowWidth(MappedTable table, SourceRow row, Diagnostics diagnostics) {
        if (table.isWiderThanHeader(row)) {
            diagnostics.add(ImportDiagnostic.row(table.sheet(), row.lineNumber(),
                    ImportSeverity.WARNING, ImportCheck.ROW_WIDTH_MISMATCH, null,
                    ("This row has %d cells but the header has %d columns; the surplus is ignored. "
                            + "Usually an unescaped delimiter inside a value.")
                            .formatted(row.cells().size(), table.table().headers().size())));
        }
    }

    /** Names are matched case-insensitively across sheets, the way {@code uq_node} does in MySQL. */
    static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** How many rows were dropped, which is what decides {@link StagedNetwork#complete()}. */
    private static final class Tally {

        private int dropped;

        void dropped() {
            dropped++;
        }

        boolean droppedAny() {
            return dropped > 0;
        }
    }

    /**
     * Typed access to one row's cells, reporting as it goes.
     *
     * <p>Every accessor follows the same three rules, which between them are the whole of stage 1's
     * type and range checking:
     *
     * <ol>
     *   <li>An empty cell is <em>absent</em>, and yields {@link Optional#empty()} without complaint —
     *       the caller applies the column's documented default. Except for a required column, where
     *       empty is {@link ImportCheck#REQUIRED_VALUE_MISSING}.</li>
     *   <li>A cell that is present but cannot be read is an error naming the column, the value and
     *       what was expected, and yields empty. The caller then knows the row is unusable through
     *       {@link #failed()} rather than by inspecting each result.</li>
     *   <li>Nothing is coerced silently. There is no "treat a blank probability as 1" or "read
     *       {@code yes} as a number" anywhere.</li>
     * </ol>
     */
    private static final class Cells {

        private final MappedTable table;
        private final SourceRow row;
        private final Diagnostics diagnostics;
        private boolean failed;

        Cells(MappedTable table, SourceRow row, Diagnostics diagnostics) {
            this.table = table;
            this.row = row;
            this.diagnostics = diagnostics;
        }

        /** True once any cell of this row was present and unreadable. */
        boolean failed() {
            return failed;
        }

        private String raw(String column) {
            return table.value(row, column);
        }

        private void error(String column, ImportCheck code, String value, String message) {
            failed = true;
            diagnostics.error(table.sheet(), row.lineNumber(), column, code, value, message);
        }

        // ------------------------------------------------------------------- text

        Optional<String> text(String column, int maxLength) {
            String value = raw(column);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            if (value.length() > maxLength) {
                error(column, ImportCheck.VALUE_TOO_LONG, value,
                        "'%s' is %d characters; %s holds at most %d."
                                .formatted(value, value.length(), column, maxLength));
                return Optional.empty();
            }
            return Optional.of(value);
        }

        Optional<String> requiredText(String column, int maxLength) {
            if (!table.has(column)) {
                // The missing column was already reported once for the sheet; repeating it per row
                // would bury every other finding under one fixable mistake.
                failed = true;
                return Optional.empty();
            }
            String value = raw(column);
            if (value.isEmpty()) {
                error(column, ImportCheck.REQUIRED_VALUE_MISSING, null,
                        "%s is required and this row leaves it empty.".formatted(column));
                return Optional.empty();
            }
            return text(column, maxLength);
        }

        // ---------------------------------------------------------------- numbers

        Optional<Double> number(String column) {
            String value = raw(column);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            // A thousands separator is what a spreadsheet leaves behind when a formatted cell is
            // exported to CSV; a comma decimal point is what a European locale writes. Neither is
            // ambiguous once the other is ruled out, and rejecting them would fail files that are
            // correct in every way the user can see.
            String cleaned = value.replace("\u00A0", "").replace(" ", "");
            if (cleaned.indexOf(',') >= 0 && cleaned.indexOf('.') < 0) {
                cleaned = cleaned.replace(',', '.');
            } else {
                cleaned = cleaned.replace(",", "");
            }
            try {
                double parsed = Double.parseDouble(cleaned);
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException(value);
                }
                return Optional.of(parsed);
            } catch (NumberFormatException notANumber) {
                error(column, ImportCheck.NOT_A_NUMBER, value,
                        "%s must be a number, and this row has '%s'.".formatted(column, value));
                return Optional.empty();
            }
        }

        Optional<Double> nonNegative(String column) {
            Optional<Double> parsed = number(column);
            if (parsed.isPresent() && parsed.get() < 0) {
                error(column, ImportCheck.OUT_OF_RANGE, raw(column),
                        "%s must not be negative, and this row has %s."
                                .formatted(column, raw(column)));
                return Optional.empty();
            }
            return parsed;
        }

        Optional<Double> positiveNumber(String column) {
            Optional<Double> parsed = number(column);
            if (parsed.isPresent() && parsed.get() <= 0) {
                error(column, ImportCheck.OUT_OF_RANGE, raw(column),
                        "%s must be greater than zero, and this row has %s."
                                .formatted(column, raw(column)));
                return Optional.empty();
            }
            if (parsed.isEmpty() && raw(column).isEmpty() && isRequired(column)) {
                error(column, ImportCheck.REQUIRED_VALUE_MISSING, null,
                        "%s is required and this row leaves it empty.".formatted(column));
            }
            return parsed;
        }

        Optional<Double> bounded(String column, double low, double high) {
            Optional<Double> parsed = number(column);
            if (parsed.isPresent() && (parsed.get() < low || parsed.get() > high)) {
                error(column, ImportCheck.OUT_OF_RANGE, raw(column),
                        "%s must be between %s and %s, and this row has %s."
                                .formatted(column, low, high, raw(column)));
                return Optional.empty();
            }
            return parsed;
        }

        Optional<Double> probability(String column) {
            Optional<Double> parsed = number(column);
            if (parsed.isPresent() && (parsed.get() < 0 || parsed.get() > 1)) {
                error(column, ImportCheck.PROBABILITY_OUT_OF_RANGE, raw(column),
                        ("%s is a probability and must be between 0 and 1, and this row has %s. A "
                                + "percentage goes in as 0.05, not 5.")
                                .formatted(column, raw(column)));
                return Optional.empty();
            }
            return parsed;
        }

        Optional<Integer> positiveInteger(String column) {
            Optional<Double> parsed = number(column);
            if (parsed.isEmpty()) {
                if (raw(column).isEmpty() && isRequired(column)) {
                    error(column, ImportCheck.REQUIRED_VALUE_MISSING, null,
                            "%s is required and this row leaves it empty.".formatted(column));
                }
                return Optional.empty();
            }
            double value = parsed.get();
            if (value != Math.rint(value) || value < 1 || value > Integer.MAX_VALUE) {
                error(column, ImportCheck.OUT_OF_RANGE, raw(column),
                        "%s must be a whole number of at least 1, and this row has %s."
                                .formatted(column, raw(column)));
                return Optional.empty();
            }
            return Optional.of((int) value);
        }

        // ------------------------------------------------------------------ enums

        Optional<NodeType> nodeType(String column) {
            if (!table.has(column)) {
                failed = true;
                return Optional.empty();
            }
            String value = raw(column);
            if (value.isEmpty()) {
                error(column, ImportCheck.REQUIRED_VALUE_MISSING, null,
                        "type is required: one of SUPPLIER, PLANT, DC, CUSTOMER.");
                return Optional.empty();
            }
            for (NodeType type : NodeType.values()) {
                if (type.name().equalsIgnoreCase(value.trim())) {
                    return Optional.of(type);
                }
            }
            error(column, ImportCheck.UNKNOWN_ENUM_VALUE, value,
                    "'%s' is not a node type. Expected one of SUPPLIER, PLANT, DC, CUSTOMER."
                            .formatted(value));
            return Optional.empty();
        }

        /**
         * A yes/no cell, through the lenient token table of {@link UnitTokens#flag} (FR-30).
         *
         * <p>Empty is absent and yields empty without complaint, so the caller applies the
         * documented default — <em>visible</em>. A token that is present and
         * unreadable is an error naming the accepted spellings, because the file did state
         * something and reading {@code maybe} as "show it" would put on screen the one thing the
         * author may have been trying to hide.
         */
        Optional<Boolean> flag(String column) {
            String value = raw(column);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            Optional<Boolean> parsed = UnitTokens.flag(value);
            if (parsed.isEmpty()) {
                error(column, ImportCheck.UNKNOWN_ENUM_VALUE, value,
                        ("'%s' is not a yes/no value. %s accepts, case-insensitively: %s. Leave the "
                                + "cell empty for the default.")
                                .formatted(value, column, UnitTokens.acceptedFlagTokens()));
            }
            return parsed;
        }

        Optional<RoundingPolicy> roundingPolicy(String column) {
            String value = raw(column);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            Optional<RoundingPolicy> policy = UnitTokens.roundingPolicy(value);
            if (policy.isEmpty()) {
                error(column, ImportCheck.UNKNOWN_ENUM_VALUE, value,
                        "'%s' is not a rounding policy. Expected NEAREST, UP or DOWN."
                                .formatted(value));
            }
            return policy;
        }

        // ------------------------------------------------------------------ units

        /**
         * The unit named by a {@code *_unit} column.
         *
         * <p>Empty when the column is absent or the cell blank — the caller then falls back to the
         * network's period unit, which is the rule. A token that is present and
         * unrecognised is an error, because the file did state a unit.
         */
        Optional<TimeUnit> timeUnit(String column) {
            String value = raw(column);
            if (value.isEmpty()) {
                if (isRequired(column)) {
                    error(column, ImportCheck.REQUIRED_VALUE_MISSING, null,
                            "%s is required.".formatted(column));
                }
                return Optional.empty();
            }
            Optional<TimeUnit> unit = UnitTokens.timeUnit(value);
            if (unit.isEmpty()) {
                error(column, ImportCheck.UNKNOWN_TIME_UNIT, value,
                        ("'%s' is not a time unit. Units are case-insensitive and accept "
                                + "abbreviations: %s.")
                                .formatted(value, UnitTokens.acceptedTimeUnitTokens()));
            }
            return unit;
        }

        /**
         * A duration from its value and unit columns; zero in {@code fallbackUnit} when the value is
         * absent.
         */
        DurationAmount duration(String valueColumn, String unitColumn, TimeUnit fallbackUnit) {
            Optional<Double> value = nonNegative(valueColumn);
            TimeUnit unit = timeUnit(unitColumn).orElse(fallbackUnit);
            return DurationAmount.of(value.orElse(0d), unit);
        }

        /**
         * A rate from its value and unit columns.
         *
         * @param absentValue what an empty value column means: null for a capacity (unconstrained,
         *                    which is what the nullable column expresses) or 0 for a demand or holding
         *                    cost, whose columns are not nullable
         */
        Rate rate(String valueColumn, String unitColumn, TimeUnit fallbackUnit, Double absentValue) {
            Optional<Double> value = nonNegative(valueColumn);
            TimeUnit unit = timeUnit(unitColumn).orElse(fallbackUnit);
            return Rate.of(value.orElse(absentValue), unit);
        }

        /**
         * True when this column is required <em>and</em> the sheet actually has it.
         *
         * <p>The second half matters: a required column that is missing entirely is reported once
         * against the sheet ({@link ImportCheck#MISSING_REQUIRED_COLUMN}), and repeating it as an empty
         * cell on every row would bury every other finding under one fixable mistake.
         */
        private boolean isRequired(String column) {
            return table.has(column) && ImportSchema.columnOf(table.sheet(), column)
                    .map(ColumnSpec::required)
                    .orElse(false);
        }
    }
}
