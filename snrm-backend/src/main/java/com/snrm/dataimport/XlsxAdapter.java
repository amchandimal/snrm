package com.snrm.dataimport;

import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads every canonical sheet of an {@code .xlsx} workbook with POI's streaming reader.
 *
 * <p><strong>Why the event model and not {@code WorkbookFactory}.</strong> Large sheets need a
 * streaming reader, and the difference is not cosmetic: the DOM-style
 * {@code XSSFWorkbook} materialises every cell of every sheet as an object graph before the first
 * value can be read, which for the 1,000-node networks of FR-04 with their product rows is a
 * multiple of the file size in heap, held for the duration of the parse. {@link XSSFReader} with
 * {@link XSSFSheetXMLHandler} hands cells over as SAX events, so the live cell set is one row plus
 * the shared-strings table rather than the whole workbook. What this adapter then keeps is only the
 * raw strings of the sheets it recognises. (The zip container itself is opened over the already
 * buffered upload, so the saving here is the cell objects, which are the expensive part.)
 *
 * <p><strong>Which sheets are read.</strong> Those whose names match a canonical sheet through
 * {@link ImportSheet#ofName}, so a workbook may carry notes, a working sheet or a chart alongside the
 * five — the caller reports the rest as unrecognised without failing the import.
 *
 * <p><strong>Cells are read as text, deliberately.</strong> Stage 1 parses from the text, because a
 * diagnostic has to quote what the user typed: a {@code failure_prob} cell holding {@code 1.4} must be
 * reported with that text rather than as a coerced value. {@link RawNumberFormatter} is what keeps the
 * text faithful to the number — a plain {@link DataFormatter} renders each cell the way the
 * spreadsheet <em>displays</em> it, so a capacity formatted with a thousands separator would arrive as
 * {@code 1,200} and a probability formatted as a percentage as {@code 5%}, neither of which is the
 * value.
 *
 * <p>Blank rows are skipped and rows are padded to the header width, so a sheet whose trailing empty
 * cells were never written reads the same as one where they were.
 */
@Component
@Order(20)
public class XlsxAdapter implements DataSourceAdapter {

    /**
     * Hand over the computed value of a formula cell, not its text.
     *
     * <p>A capacity computed as {@code =B2*1.2} is data like any other; the formula is the user's
     * working and means nothing to the importer.
     */
    private static final boolean FORMULAS_NOT_RESULTS = false;

    /**
     * {@code .xlsx} and the macro-enabled {@code .xlsm}, which is the same OOXML container.
     *
     * <p>The legacy binary {@code .xls} is deliberately not supported: it needs POI's HSSF, which has
     * no streaming reader at all, and the supported workbook form is xlsx. Such a file falls
     * through every adapter and is reported as an unreadable format, which names the problem.
     */
    @Override
    public boolean supports(UploadedFile file) {
        String extension = file.extension();
        return extension.equals("xlsx") || extension.equals("xlsm");
    }

    @Override
    public List<SourceTable> read(UploadedFile file) throws IOException {
        List<SourceTable> tables = new ArrayList<>();
        try (OPCPackage archive = OPCPackage.open(file.open())) {
            XSSFReader reader = new XSSFReader(archive);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(archive);
            StylesTable styles = reader.getStylesTable();

            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    String sheetName = sheets.getSheetName();
                    Optional<ImportSheet> sheet = ImportSheet.ofName(sheetName);
                    if (sheet.isEmpty()) {
                        continue; // a notes or working sheet; the caller reports it as unrecognised
                    }
                    tables.add(readSheet(sheet.get(), sheetName, sheetStream, styles, strings));
                }
            }
        } catch (OpenXML4JException | SAXException | ParserConfigurationException failure) {
            throw new IOException("%s is not a readable .xlsx workbook: %s"
                    .formatted(file.filename(), failure.getMessage()), failure);
        }
        return tables;
    }

    @Override
    public String formatName() {
        return "XLSX";
    }

    private static SourceTable readSheet(ImportSheet sheet, String sheetName, InputStream stream,
            StylesTable styles, ReadOnlySharedStringsTable strings)
            throws IOException, SAXException, ParserConfigurationException {

        RowCollector collector = new RowCollector();
        XMLReader parser = org.apache.poi.util.XMLHelper.newXMLReader();
        parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, collector,
                new RawNumberFormatter(), FORMULAS_NOT_RESULTS));
        parser.parse(new InputSource(stream));

        return new SourceTable(sheet, sheetName, "XLSX", collector.headers, collector.padded());
    }

    /**
     * A {@link DataFormatter} that renders a number as a number.
     *
     * <p>Excel's cell format is a display choice, not part of the value: the same 1200 may be shown as
     * {@code 1,200}, {@code 1.2E3} or {@code $1,200.00}. Passing any of those on as the cell's text
     * would make a perfectly valid capacity fail stage 1's number check, which is a failure the user
     * cannot even see the cause of — the spreadsheet shows them what they meant.
     * {@link NumberToTextConverter} produces Excel's own General rendering, which is what a plain
     * numeric cell would have displayed anyway.
     *
     * <p>Date-formatted cells keep the inherited behaviour. No column is a date, so this is
     * only so that a stray date column reads as a date in a diagnostic rather than as 45,678.
     */
    private static final class RawNumberFormatter extends DataFormatter {

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString,
                boolean use1904Windowing) {
            if (DateUtil.isADateFormat(formatIndex, formatString)) {
                return super.formatRawCellContents(value, formatIndex, formatString, use1904Windowing);
            }
            return NumberToTextConverter.toText(value);
        }
    }

    /**
     * Collects the sheet's rows as they stream past.
     *
     * <p>The first non-blank row is the header; the rest are data. Cells arrive with their spreadsheet
     * reference ({@code C7}), which is the only way to know a row skipped a column — the event model
     * emits nothing at all for an empty cell, so a row read naively would shift every value left of
     * the gap into the wrong column.
     */
    private static final class RowCollector implements SheetContentsHandler {

        private final List<String> headers = new ArrayList<>();
        private final List<SourceTable.SourceRow> rows = new ArrayList<>();
        private List<String> current = new ArrayList<>();
        private int currentLine;

        @Override
        public void startRow(int rowNumber) {
            current = new ArrayList<>();
            currentLine = rowNumber + 1; // POI counts from 0; the user's row numbers start at 1
        }

        @Override
        public void endRow(int rowNumber) {
            SourceTable.SourceRow row = new SourceTable.SourceRow(currentLine, current);
            if (row.isBlank()) {
                return;
            }
            if (headers.isEmpty()) {
                headers.addAll(current);
                return;
            }
            rows.add(row);
        }

        @Override
        public void cell(String reference, String formattedValue, XSSFComment comment) {
            int column = reference == null ? current.size() : columnOf(reference);
            while (current.size() < column) {
                current.add("");
            }
            current.add(formattedValue == null ? "" : formattedValue);
        }

        /** {@code AB7} → 27: the zero-based column index of a spreadsheet cell reference. */
        private static int columnOf(String reference) {
            int column = 0;
            for (int i = 0; i < reference.length(); i++) {
                char character = reference.charAt(i);
                if (character < 'A' || character > 'Z') {
                    break;
                }
                column = column * 26 + (character - 'A' + 1);
            }
            return column - 1;
        }

        /**
         * The data rows, every one padded to the header width.
         *
         * <p>So a row whose trailing cells were never written reads as empty strings rather than as a
         * short row, which is the difference between "this optional column defaulted" and a spurious
         * row-length complaint. Rows that are genuinely <em>longer</em> than the header are left long
         * — that is a real mismatch and stage 1 says so.
         */
        List<SourceTable.SourceRow> padded() {
            List<SourceTable.SourceRow> result = new ArrayList<>(rows.size());
            for (SourceTable.SourceRow row : rows) {
                if (row.cells().size() >= headers.size()) {
                    result.add(row);
                    continue;
                }
                List<String> cells = new ArrayList<>(row.cells());
                while (cells.size() < headers.size()) {
                    cells.add("");
                }
                result.add(new SourceTable.SourceRow(row.lineNumber(), cells));
            }
            return result;
        }
    }
}
