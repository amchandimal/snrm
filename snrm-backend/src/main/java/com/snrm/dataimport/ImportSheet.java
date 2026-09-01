package com.snrm.dataimport;

import java.util.Locale;
import java.util.Optional;

/**
 * The five files — or the five workbook sheets — a network is imported from and exported to.
 *
 * <p>One canonical name each, which is both the CSV file stem and the Excel sheet name: the two
 * forms of the same import carry identical identity, so a workbook produced by the export
 * unzips conceptually into the CSV set and back. {@link #declarationOrder()} is the order
 * they are read and written in, and it is a dependency order rather than a preference —
 * {@code links} resolves node names, {@code node_products} resolves both node and product names, so
 * nothing can be validated before the sheet it points at has been read.
 *
 * <p>Only {@link #NODES} is indispensable. {@link #NETWORK_META} and {@link #PRODUCTS} have
 * documented defaults, {@link #LINKS} and {@link #NODE_PRODUCTS} may legitimately be empty for a
 * network that is still being drawn — though a network without links will fail the customer
 * reachability check of stage 2, which is the correct place for that complaint rather than here.
 */
public enum ImportSheet {

    /**
     * The network's time base. Optional: absent means 1 DAY / NEAREST and a warning
     * asking the wizard to confirm.
     */
    NETWORK_META("network_meta"),

    /** The facilities. The one sheet an import cannot do without. */
    NODES("nodes"),

    /** The directed arcs, resolving {@code source} and {@code target} against {@link #NODES}. */
    LINKS("links"),

    /** The product catalogue. Absent means a single default product is created. */
    PRODUCTS("products"),

    /** Per-node, per-product demand, inventory and holding cost. */
    NODE_PRODUCTS("node_products");

    private final String canonicalName;

    ImportSheet(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    /** The CSV file stem and the Excel sheet name — {@code nodes}, {@code node_products}. */
    public String canonicalName() {
        return canonicalName;
    }

    /** Read and write order: a sheet never precedes one it resolves names against. */
    public static ImportSheet[] declarationOrder() {
        return values();
    }

    /**
     * The sheet a file or worksheet name denotes, if any.
     *
     * <p>Tolerant in exactly the ways a real upload is untidy and no further: case is ignored, a
     * {@code .csv}/{@code .xlsx} extension and any directory prefix are stripped, and spaces,
     * hyphens and repeated underscores all read as a single underscore — so {@code Node Products}
     * ,{@code node-products.CSV} and {@code NODE_PRODUCTS} are one sheet. A name that still does not
     * match is reported as an unrecognised sheet and skipped, never guessed at: silently importing
     * {@code nodes_v2.csv} as {@code nodes} is how the wrong file becomes a network.
     */
    public static Optional<ImportSheet> ofName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalised = normalise(rawName);
        for (ImportSheet sheet : values()) {
            if (sheet.canonicalName.equals(normalised)) {
                return Optional.of(sheet);
            }
        }
        return Optional.empty();
    }

    /** {@code data/Node Products.csv} → {@code node_products}. */
    static String normalise(String rawName) {
        String name = rawName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("_+", "_");
    }
}
