package com.snrm.dataimport;

/**
 * The element and attribute names of the XML interchange format.
 *
 * <p>Declared once and shared by {@link XmlAdapter} and the writer in {@link NetworkExportService},
 * for the same reason {@link ImportSchema} is shared by the tabular reader and writer: a document that
 * cannot be re-read is worse than no export at all, and two lists of names is how that happens.
 *
 * <p>The mapping from these names to the canonical columns lives here too
 * ({@link #NODE_ATTRIBUTES} and friends), which is what lets an XML document be validated by exactly
 * the same two stages as a spreadsheet — the adapter turns elements into rows and everything
 * downstream is none the wiser.
 */
final class XmlSchema {

    /** Root element. */
    static final String NETWORK = "snrm-network";

    /**
     * The document format this code reads and writes.
     *
     * <p>Checked on read and refused if unknown, rather than parsed optimistically until something
     * stops making sense. Bumped only when the document's shape changes incompatibly;
     * adding an optional attribute is not that, because an older reader ignores it and a newer one
     * defaults it.
     */
    static final int SCHEMA_VERSION = 1;

    static final String ATTR_SCHEMA_VERSION = "schemaVersion";

    // ---------------------------------------------------------------- container elements

    static final String META = "meta";
    static final String NODES = "nodes";
    static final String LINKS = "links";
    static final String PRODUCTS = "products";
    static final String NODE_PRODUCTS = "nodeProducts";

    // ------------------------------------------------------------------- row elements

    static final String NODE = "node";
    static final String LINK = "link";
    static final String PRODUCT = "product";
    static final String NODE_PRODUCT = "nodeProduct";

    // ------------------------------------------------------ unit-bearing child elements

    /**
     * A quantity and its unit is a child element, never a pair of attributes.
     *
     * <p>{@code <leadTime value="3" unit="DAY"/>} rather than {@code leadTime="3" leadTimeUnit="DAY"},
     * so an edit cannot separate the two halves of one value — the same reason the entities store them
     * as a single embeddable. {@link #ATTR_VALUE} is shared; the unit attribute differs by kind
     * ({@link #ATTR_UNIT} for a duration's unit, {@link #ATTR_TIME_UNIT} for a rate's denominator),
     * following the column names.
     */
    static final String PERIOD_LENGTH = "periodLength";
    static final String CAPACITY = "capacity";
    static final String PROCESSING_TIME = "processingTime";
    static final String LEAD_TIME = "leadTime";
    static final String DEMAND = "demand";
    static final String HOLDING_COST = "holdingCost";

    static final String ATTR_VALUE = "value";
    static final String ATTR_UNIT = "unit";
    static final String ATTR_TIME_UNIT = "timeUnit";

    // ------------------------------------------------- attribute → canonical column maps

    /**
     * Plain attributes of {@code <meta>}, paired with the {@code network_meta} column each fills.
     *
     * <p>{@code periodLength} is absent from this list because it is an element, not an attribute; the
     * adapter expands it into the two columns {@code period_length_value} and
     * {@code period_length_unit}.
     */
    static final String[][] META_ATTRIBUTES = {
            {"name", "name"},
            {"horizonPeriods", "horizon_periods"},
            {"roundingPolicy", "rounding_policy"},
    };

    static final String[][] NODE_ATTRIBUTES = {
            {"name", "name"},
            {"type", "type"},
            {"fixedCost", "fixed_cost"},
            {"varCost", "var_cost"},
            {"failureProb", "failure_prob"},
            {"region", "region"},
            {"lat", "lat"},
            {"lng", "lng"},
            {"posX", "pos_x"},
            {"posY", "pos_y"},
            // FR-30. Plain attributes rather than a child element: a caption carries no
            // unit, so the rule that keeps a value and its unit inseparable does not apply, and the
            // pair is the plainest shape that carries both. An omitted captionVisible is
            // read as VISIBLE, which is what the tabular caption_visible column already means.
            {"caption", "caption"},
            {"captionVisible", "caption_visible"},
    };

    static final String[][] LINK_ATTRIBUTES = {
            {"source", "source"},
            {"target", "target"},
            {"unitCost", "unit_cost"},
            {"failureProb", "failure_prob"},
            {"caption", "caption"},
            {"captionVisible", "caption_visible"},
    };

    static final String[][] PRODUCT_ATTRIBUTES = {
            {"name", "name"},
            {"unitValue", "unit_value"},
    };

    static final String[][] NODE_PRODUCT_ATTRIBUTES = {
            {"node", "node"},
            {"product", "product"},
            {"initialInventory", "initial_inventory"},
            {"safetyStock", "safety_stock"},
    };

    /**
     * Unit-bearing child elements of a row element: element name, value column, unit column, and
     * whether the unit attribute is {@code unit} (a duration) or {@code timeUnit} (a rate).
     */
    record Quantity(String element, String valueColumn, String unitColumn, boolean duration) {

        String unitAttribute() {
            return duration ? ATTR_UNIT : ATTR_TIME_UNIT;
        }
    }

    static final Quantity[] NODE_QUANTITIES = {
            new Quantity(CAPACITY, "capacity_value", "capacity_time_unit", false),
            new Quantity(PROCESSING_TIME, "processing_time_value", "processing_time_unit", true),
    };

    static final Quantity[] LINK_QUANTITIES = {
            new Quantity(LEAD_TIME, "lead_time_value", "lead_time_unit", true),
            new Quantity(CAPACITY, "capacity_value", "capacity_time_unit", false),
    };

    static final Quantity[] NODE_PRODUCT_QUANTITIES = {
            new Quantity(DEMAND, "demand_value", "demand_time_unit", false),
            new Quantity(HOLDING_COST, "holding_cost_value", "holding_cost_time_unit", false),
    };

    static final Quantity[] META_QUANTITIES = {
            new Quantity(PERIOD_LENGTH, "period_length_value", "period_length_unit", true),
    };

    private XmlSchema() {
    }

    /** The row element a canonical sheet is written as. */
    static String rowElementOf(ImportSheet sheet) {
        return switch (sheet) {
            case NETWORK_META -> META;
            case NODES -> NODE;
            case LINKS -> LINK;
            case PRODUCTS -> PRODUCT;
            case NODE_PRODUCTS -> NODE_PRODUCT;
        };
    }

    /** The container element a canonical sheet's rows sit in; {@code meta} has none. */
    static String containerOf(ImportSheet sheet) {
        return switch (sheet) {
            case NETWORK_META -> null;
            case NODES -> NODES;
            case LINKS -> LINKS;
            case PRODUCTS -> PRODUCTS;
            case NODE_PRODUCTS -> NODE_PRODUCTS;
        };
    }

    static String[][] attributesOf(ImportSheet sheet) {
        return switch (sheet) {
            case NETWORK_META -> META_ATTRIBUTES;
            case NODES -> NODE_ATTRIBUTES;
            case LINKS -> LINK_ATTRIBUTES;
            case PRODUCTS -> PRODUCT_ATTRIBUTES;
            case NODE_PRODUCTS -> NODE_PRODUCT_ATTRIBUTES;
        };
    }

    static Quantity[] quantitiesOf(ImportSheet sheet) {
        return switch (sheet) {
            case NETWORK_META -> META_QUANTITIES;
            case NODES -> NODE_QUANTITIES;
            case LINKS -> LINK_QUANTITIES;
            case PRODUCTS -> new Quantity[0];
            case NODE_PRODUCTS -> NODE_PRODUCT_QUANTITIES;
        };
    }
}
