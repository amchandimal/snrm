package com.snrm.dataimport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The growing list of findings for one import, and the order it is read in.
 *
 * <p>A mutable collector rather than a returned list per check, because the two stages
 * produce findings from a dozen places — per sheet, per row, per cell, then per graph — and threading
 * an immutable list through all of them buys nothing here. It is confined to a single import call and
 * never shared, so it needs no synchronisation.
 *
 * <p>{@link #sorted()} is the only order the wizard sees: errors before warnings, then by sheet in
 * schema order, then by line, then by column. That is the order a user works through a broken file in —
 * worst first, and within that, top to bottom of each sheet — rather than the order the checks happened
 * to run in, which is an implementation detail that would reshuffle whenever a check moved.
 */
final class Diagnostics {

    /**
     * Errors first, then schema order, then line, then column.
     *
     * <p>{@link ImportSheet} is compared by its declaration order rather than its name, so
     * {@code nodes} precedes {@code links} precedes {@code node_products} — the dependency order the
     * file is actually read in, and the order a fix has to be applied in. Alphabetically,
     * {@code links} would come first and a user would be reading about unresolved node references
     * before the node rows that failed.
     */
    private static final Comparator<ImportDiagnostic> WORST_FIRST =
            Comparator.comparing(ImportDiagnostic::severity, Comparator.reverseOrder())
                    .thenComparingInt(Diagnostics::sheetOrder)
                    .thenComparing(ImportDiagnostic::line,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ImportDiagnostic::column,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ImportDiagnostic::code);

    private final List<ImportDiagnostic> found = new ArrayList<>();

    void add(ImportDiagnostic diagnostic) {
        found.add(diagnostic);
    }

    void addAll(List<ImportDiagnostic> diagnostics) {
        found.addAll(diagnostics);
    }

    /**
     * A cell-level error — the shorthand for the common case of stage 1.
     *
     * <p>There is deliberately no {@code warn} twin. Every warning this importer raises is about a row,
     * a sheet or the graph rather than about one cell's value: a cell whose value cannot be read is an
     * error by definition, because the alternative is to invent something to put in it. Adding the
     * shorthand before a use for it exists would invite exactly that.
     */
    void error(ImportSheet sheet, int line, String column, ImportCheck code, String value,
            String message) {
        add(ImportDiagnostic.cell(sheet, line, column, ImportSeverity.ERROR, code, value, message));
    }

    /** True once anything would block the import — checked before persisting. */
    boolean hasErrors() {
        for (ImportDiagnostic diagnostic : found) {
            if (diagnostic.isError()) {
                return true;
            }
        }
        return false;
    }

    int errorCount() {
        int count = 0;
        for (ImportDiagnostic diagnostic : found) {
            if (diagnostic.isError()) {
                count++;
            }
        }
        return count;
    }

    int warningCount() {
        return found.size() - errorCount();
    }

    /** Every finding, worst first — the order the validation report lists them in. */
    List<ImportDiagnostic> sorted() {
        List<ImportDiagnostic> sorted = new ArrayList<>(found);
        sorted.sort(WORST_FIRST);
        return List.copyOf(sorted);
    }

    private static int sheetOrder(ImportDiagnostic diagnostic) {
        if (diagnostic.sheet() == null) {
            return -1; // findings about the upload as a whole come before any sheet's
        }
        ImportSheet[] sheets = ImportSheet.declarationOrder();
        for (int i = 0; i < sheets.length; i++) {
            if (sheets[i].canonicalName().equals(diagnostic.sheet())) {
                return i;
            }
        }
        return sheets.length;
    }
}
