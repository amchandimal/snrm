package com.snrm.dataimport;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an upload into the canonical sheets it contains, whatever form it arrived in.
 *
 * <p>The one place the {@link DataSourceAdapter} implementations are chosen between, so no caller has
 * to know whether it is holding a workbook or a set of CSVs — which is what makes "either CSV files or
 * one Excel workbook" a statement about the upload rather than a branch in the importer. Adapters are
 * injected in {@code @Order}, and the first whose {@link DataSourceAdapter#supports} answers yes reads
 * the file.
 *
 * <p><strong>Nothing here is fatal.</strong> An unreadable file, a sheet nobody recognises, the same
 * sheet arriving twice — each becomes a diagnostic and the rest of the upload is still read. That is
 * what lets step 3 of the wizard show every problem in one pass instead of one per attempt, and it costs
 * nothing in safety because no network is created while an error stands.
 */
@Component
public class SourceReader {

    private final List<DataSourceAdapter> adapters;

    SourceReader(List<DataSourceAdapter> adapters) {
        this.adapters = adapters;
    }

    /**
     * Reads every recognised sheet out of a multipart upload — the wizard's path.
     *
     * <p>Buffers each part into an {@link UploadedFile} and hands off to {@link #readFiles}. A part
     * that cannot be read becomes a diagnostic and the rest are still read, as everything here does.
     *
     * @param files       the multipart parts, in the order they were sent
     * @param diagnostics collector for unreadable files, unrecognised names and duplicate sheets
     * @return one table per canonical sheet found; a sheet that arrived twice keeps the first
     */
    public Map<ImportSheet, SourceTable> read(List<MultipartFile> files, Diagnostics diagnostics) {
        List<UploadedFile> buffered = new ArrayList<>(files.size());
        for (MultipartFile part : files) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            try {
                buffered.add(UploadedFile.of(part));
            } catch (IOException unreadable) {
                diagnostics.add(ImportDiagnostic.upload(ImportSeverity.ERROR,
                        ImportCheck.UNREADABLE_FILE, part.getOriginalFilename(),
                        "Could not read the uploaded file: " + unreadable.getMessage()));
            }
        }
        return readFiles(buffered, diagnostics);
    }

    /**
     * The same read, over content that never came through a request.
     *
     * <p>{@link UploadedFile} exists precisely so the adapters need no web layer, and this is the
     * entry point that takes it: {@code com.snrm.archive} restores a project by feeding each
     * network's archived XML document through the ordinary importer, and a bundle entry is bytes,
     * not a multipart part. Everything downstream — validation, the two stages, the report — is the
     * path a hand-uploaded file takes, which is the point.
     *
     * @param files       the documents, in the order they should be read
     * @param diagnostics collector for unreadable files, unrecognised names and duplicate sheets
     */
    public Map<ImportSheet, SourceTable> readFiles(List<UploadedFile> files,
            Diagnostics diagnostics) {

        Map<ImportSheet, SourceTable> tables = new EnumMap<>(ImportSheet.class);
        for (UploadedFile file : files) {
            if (file == null || file.size() == 0) {
                continue;
            }
            readFile(file, tables, diagnostics);
        }

        if (!tables.containsKey(ImportSheet.NODES)) {
            diagnostics.add(ImportDiagnostic.upload(ImportSeverity.ERROR,
                    ImportCheck.MISSING_NODES_SHEET, null,
                    ("No 'nodes' sheet in the upload. A network is imported either as CSV files named "
                            + "after the canonical sheets — nodes.csv, links.csv, products.csv, "
                            + "node_products.csv, network_meta.csv — or as one .xlsx workbook whose "
                            + "sheets carry those names. Files seen: %s.")
                            .formatted(names(files))));
        }
        return tables;
    }

    private void readFile(UploadedFile file, Map<ImportSheet, SourceTable> tables,
            Diagnostics diagnostics) {

        DataSourceAdapter adapter = adapterFor(file);
        if (adapter == null) {
            diagnostics.add(ImportDiagnostic.upload(ImportSeverity.ERROR,
                    ImportCheck.UNREADABLE_FILE, file.filename(),
                    ("'%s' is not a format this import reads. Expected .csv, .xlsx for a workbook, "
                            + "or .xml for an interchange document. The legacy "
                            + "binary .xls is not supported — save it as .xlsx.")
                            .formatted(file.filename())));
            return;
        }

        List<SourceTable> read;
        try {
            read = adapter.read(file);
        } catch (IOException malformed) {
            diagnostics.add(ImportDiagnostic.upload(ImportSeverity.ERROR,
                    ImportCheck.UNREADABLE_FILE, file.filename(),
                    "'%s' could not be parsed as %s: %s"
                            .formatted(file.filename(), adapter.formatName(), malformed.getMessage())));
            return;
        }

        if (read.isEmpty()) {
            diagnostics.add(ImportDiagnostic.upload(ImportSeverity.WARNING,
                    ImportCheck.UNRECOGNISED_SHEET, file.filename(),
                    ("Nothing in '%s' matches a canonical sheet, so it was skipped. A CSV is "
                            + "identified by its filename and a workbook by its sheet names; expected "
                            + "network_meta, nodes, links, products or node_products.")
                            .formatted(file.filename())));
            return;
        }

        for (SourceTable table : read) {
            SourceTable existing = tables.putIfAbsent(table.sheet(), table);
            if (existing != null) {
                diagnostics.add(ImportDiagnostic.upload(ImportSeverity.ERROR,
                        ImportCheck.DUPLICATE_SHEET, table.sheet().canonicalName(),
                        ("The '%s' sheet arrived twice — from '%s' and from '%s'. Send each sheet "
                                + "once: either the CSV set or the workbook, not both.")
                                .formatted(table.sheet().canonicalName(), existing.sourceName(),
                                        table.sourceName())));
            }
        }
    }

    private DataSourceAdapter adapterFor(UploadedFile file) {
        for (DataSourceAdapter adapter : adapters) {
            if (adapter.supports(file)) {
                return adapter;
            }
        }
        return null;
    }

    /**
     * The files that were readable, for the "Files seen" half of {@code MISSING_NODES_SHEET}.
     *
     * <p>A part that could not be buffered at all is absent here and has already raised its own
     * {@code UNREADABLE_FILE} diagnostic naming it, so the list says what was read rather than what
     * was sent — which is the more useful thing to see next to "no nodes sheet".
     */
    private static String names(List<UploadedFile> files) {
        List<String> names = new ArrayList<>();
        for (UploadedFile file : files) {
            if (file != null && file.size() > 0) {
                names.add(file.filename());
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
