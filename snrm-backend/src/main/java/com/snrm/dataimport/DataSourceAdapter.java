package com.snrm.dataimport;

import java.io.IOException;
import java.util.List;

/**
 * The SPI for reading a network out of an uploaded file.
 *
 * <p>There are two forms — a set of CSV files, or one Excel workbook whose sheets carry the same
 * names — and this is the seam between them and everything else. Both implementations answer with
 * {@link SourceTable}s, so mapping, validation and staging are written once. A third format is a
 * new {@code @Component} implementing this interface and no change anywhere else.
 *
 * <p>Implementations are discovered by Spring and consulted in {@code @Order}; the first one whose
 * {@link #supports} answers yes reads the file.
 */
public interface DataSourceAdapter {

    /**
     * Whether this adapter can read the file.
     *
     * @param file the uploaded part; the extension is the primary signal and the declared content
     *             type only a tiebreak, because browsers disagree about spreadsheet MIME types
     */
    boolean supports(UploadedFile file);

    /**
     * Reads every canonical table the file contains.
     *
     * <p>A CSV yields at most one — its filename names the sheet. A workbook yields one per sheet
     * whose name matches a canonical one; sheets that match nothing are simply absent from the result
     * and the caller reports them as unrecognised, because a workbook may legitimately
     * carry a notes or working sheet alongside the data.
     *
     * @throws IOException if the file is unreadable or malformed as a container — a corrupt zip, an
     *                     unterminated quoted field. A <em>value</em> that cannot be read is not an
     *                     exception; that is stage 1's business and is reported per row.
     */
    List<SourceTable> read(UploadedFile file) throws IOException;

    /** How this adapter names itself in a diagnostic — {@code CSV}, {@code XLSX}. */
    String formatName();
}
