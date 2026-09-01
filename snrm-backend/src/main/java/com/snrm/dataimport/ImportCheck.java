package com.snrm.dataimport;

/**
 * Every check the two-stage validation can report, one constant per rule.
 *
 * <p>The constant name is the {@code code} of an {@link ImportDiagnostic} and is <strong>API
 * contract</strong>: the wizard groups by it, decides what to show inline against a cell, and knows
 * which codes the mapping step can fix. Treat a rename as a breaking change; the
 * {@code message} beside it is prose and may be reworded freely.
 *
 * <p>Grouped by the two stages. Stage 1 is about a cell or a row and always carries a line
 * number; stage 2 is about the graph and usually carries none, because "customer C-3 has no inbound
 * path" is not a statement about any one row.
 */
public enum ImportCheck {

    // ------------------------------------------------------------ files and columns

    /** A file or worksheet whose name matches no canonical sheet. Skipped, not fatal. */
    UNRECOGNISED_SHEET,

    /** No adapter could read the file — a {@code .xls}, a PDF, a corrupt archive. */
    UNREADABLE_FILE,

    /** The same canonical sheet arrived twice, e.g. a workbook plus a loose {@code nodes.csv}. */
    DUPLICATE_SHEET,

    /** The upload carried no {@code nodes} sheet, the one sheet an import cannot do without. */
    MISSING_NODES_SHEET,

    /** An optional sheet is absent and its documented default applies. Always a warning. */
    SHEET_DEFAULTED,

    /**
     * Neither the import request nor the file's {@code network_meta} gives the network a name.
     *
     * <p>Reported during validation rather than raised when the network is written, so a dry run says
     * so before the user confirms an import that would then fail.
     */
    NETWORK_NAME_MISSING,

    /** A required column of the canonical schema is neither present nor mapped to. */
    MISSING_REQUIRED_COLUMN,

    /** A source column mapped to nothing and was ignored. The mapping step is where to fix it. */
    UNMAPPED_COLUMN,

    /** Two source columns were mapped onto the same canonical column. */
    AMBIGUOUS_COLUMN,

    /** A row has more cells than the header has columns; the surplus is ignored. */
    ROW_WIDTH_MISMATCH,

    // ----------------------------------------------------------------- stage 1: cells

    /** A required cell is empty. */
    REQUIRED_VALUE_MISSING,

    /** A numeric column holds something that is not a number. */
    NOT_A_NUMBER,

    /** A number outside the range the field allows — a negative cost, a latitude of 200. */
    OUT_OF_RANGE,

    /** {@code failure_prob} outside [0,1] ({@code ck_node_failure_prob}, {@code ck_link_failure_prob}). */
    PROBABILITY_OUT_OF_RANGE,

    /** A {@code type} or {@code rounding_policy} cell naming something the enum does not have. */
    UNKNOWN_ENUM_VALUE,

    /** A {@code *_unit} cell whose token is not a recognised unit ({@link UnitTokens}). */
    UNKNOWN_TIME_UNIT,

    /** A value longer than the column it has to be stored in. */
    VALUE_TOO_LONG,

    // --------------------------------------------------- stage 1: keys and references

    /** A key that must be unique within its sheet is not: a node name, a product, a link's pair. */
    DUPLICATE_KEY,

    /** A link whose source and target are the same node ({@code ck_link_no_self_loop}). */
    SELF_LOOP,

    /** A {@code links.source}/{@code target} or {@code node_products.node} naming no known node. */
    UNKNOWN_NODE_REFERENCE,

    /** A {@code node_products.product} naming neither a row of {@code products} nor an existing one. */
    UNKNOWN_PRODUCT_REFERENCE,

    /**
     * A product of the {@code products} sheet is already in the project with a different
     * {@code unit_value}. The existing row wins: products are project-scoped and shared by every
     * configuration variant, so an import that rewrote the catalogue would change the
     * economics of networks it was never meant to touch.
     */
    PRODUCT_ALREADY_IN_PROJECT,

    /**
     * A product was created because the import needed one and the {@code products} sheet was absent —
     * either the names {@code node_products} referenced, or the single default product.
     */
    PRODUCT_CREATED_IMPLICITLY,

    // ----------------------------------------------------------------- stage 2: network

    /**
     * No SUPPLIER and no PLANT: nothing in the network originates material, so no demand can ever be
     * served and every service metric would read zero for a structural reason.
     */
    NO_SUPPLY_NODE,

    /** No CUSTOMER: nothing consumes, so there is no demand to measure service against. */
    NO_CUSTOMER_NODE,

    /**
     * A customer with no directed path from any supply-side node. Its demand can never be served, and
     * a fill rate averaged over it measures the topology rather than the disruption.
     */
    CUSTOMER_UNREACHABLE,

    /**
     * A link pointing upstream — into a SUPPLIER, out of a CUSTOMER, or otherwise against the echelon
     * order. A warning, not a refusal: such a link is merely flagged, and a reverse-logistics
     * or returns arc is a legitimate thing to model deliberately.
     */
    ECHELON_DIRECTION,

    /** A link between two nodes of the same echelon — DC-to-DC transshipment. Legal, flagged. */
    LATERAL_LINK,

    /** A node with no links at all: it cannot participate in any flow. */
    ORPHAN_NODE,

    /** No customer declares a positive demand, so a simulation would have nothing to serve. */
    NO_DEMAND_DECLARED,

    /** A demand on a node that is not a CUSTOMER. Ignored by the engine. */
    DEMAND_ON_NON_CUSTOMER,

    /**
     * Stage 2 ran over a graph that stage 1 had already dropped rows from, so its findings are about
     * a partial network. Reported once, as a warning, so no stage-2 message is read as final while
     * row errors are outstanding.
     */
    NETWORK_CHECKS_ON_PARTIAL_GRAPH,

    /**
     * A network-level check could not run at all, and why. Distinct from a check that ran and passed:
     * "no customer was reachable" and "reachability was not evaluated" are different answers.
     */
    NETWORK_CHECK_SKIPPED
}
