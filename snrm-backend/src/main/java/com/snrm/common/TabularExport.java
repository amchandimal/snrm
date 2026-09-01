package com.snrm.common;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a set of named tables as one workbook or a zip of CSV files.
 *
 * <p>The results dashboard and the comparison view both need "this table, as a spreadsheet", and
 * neither table is a network — so neither can go through {@code NetworkExportService}, whose sheets
 * <em>are</em> the canonical import schema and whose third format is the XML interchange
 * document. That service exists to make a round trip structural; this one exists to hand
 * a researcher a table. Trying to serve both from one class would mean parameterising the import
 * schema away for no gain, and would put an XML branch on a path that has no interchange format
 * behind it.
 *
 * <p>What the two <em>do</em> share is the decisions below, which is why this is one class rather
 * than a copy in each exporter: UTF-8 comma-delimited CSV, a bold header row, {@link SXSSFWorkbook}
 * so a fifty-period horizon does not build a document object per cell, and one zip entry per table
 * because a CSV export of several tables is several files and a browser can be handed one.
 *
 * <p>Everything arrives as strings. Formatting a number is a decision about how a value should read
 * — {@link #number(double)} writes {@code 6} where the model holds {@code 6.0} — and making it at
 * the call site keeps this class from having an opinion about anybody's data.
 */
public final class TabularExport {

    private TabularExport() {
    }

    /**
     * One named table.
     *
     * @param name    the sheet name, and the CSV file's stem inside the zip
     * @param headers the header row, written bold in the workbook and first in the CSV
     * @param rows    the body; each row should have as many cells as there are headers
     */
    public record Table(String name, List<String> headers, List<List<String>> rows) {
    }

    /** A finished export, ready for a {@code Content-Disposition}. */
    public record File(String filename, String contentType, byte[] content) {
    }

    // -------------------------------------------------------------------------- writers

    /** One workbook, one sheet per table. */
    public static byte[] workbook(List<Table> tables) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            try {
                for (Table table : tables) {
                    Sheet sheet = workbook.createSheet(sheetName(table.name()));
                    write(sheet.createRow(0), table.headers(), headerStyle);
                    int rowIndex = 1;
                    for (List<String> row : table.rows()) {
                        write(sheet.createRow(rowIndex++), row, null);
                    }
                }
                workbook.write(out);
            } finally {
                workbook.dispose();
            }
            return out.toByteArray();
        } catch (IOException failure) {
            // Writing to a byte array cannot fail for any reason the caller can act on.
            throw new UncheckedIOException("Could not write the workbook", failure);
        }
    }

    /** A zip holding one {@code .csv} per table. */
    public static byte[] zippedCsv(List<Table> tables) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Table table : tables) {
                zip.putNextEntry(new ZipEntry(table.name() + ".csv"));
                zip.write(csv(table));
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write the CSV archive", failure);
        }
    }

    /** One table as CSV bytes — UTF-8, comma-delimited, header first. */
    public static byte[] csv(Table table) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord(table.headers());
            for (List<String> row : table.rows()) {
                printer.printRecord(row);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write the CSV table", failure);
        }
        return out.toByteArray();
    }

    private static void write(Row row, List<String> values, CellStyle style) {
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values.get(i));
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    // ------------------------------------------------------------------------ rendering

    /**
     * A number as short as it can be written without losing anything: {@code 6} rather than
     * {@code 6.0}.
     *
     * <p>The same rule {@code NetworkExportService} applies, and for the same reason: a column full
     * of {@code 6.0} where the model holds a whole number makes a diff between two exports noisy in
     * a way that has nothing to do with the networks.
     */
    public static String number(double value) {
        if (!Double.isFinite(value)) {
            return "";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%s", value);
    }

    /**
     * An optional number: empty rather than {@code 0}.
     *
     * <p>The distinction is load-bearing in both new exporters. A metric with no confidence interval
     * is exact, not zero-width; a cell with no value is unmeasured, not zero.
     */
    public static String number(Double value) {
        return value == null ? "" : number(value.doubleValue());
    }

    /** An optional string: empty for absent. */
    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** An optional instant, ISO-8601 in UTC — what the API carries and what a spreadsheet sorts. */
    public static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    /** A filename Windows and a {@code Content-Disposition} header will both accept. */
    public static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return cleaned.isEmpty() ? "export" : cleaned;
    }

    /**
     * A sheet name Excel will accept: at most 31 characters and none of {@code []:*?/\}.
     *
     * <p>Every name this application passes in is already legal, so this is a guard rather than a
     * transformation — but POI throws on a violation, and losing a whole export to a sheet title is
     * not a trade worth making.
     */
    private static String sheetName(String name) {
        String cleaned = name.replaceAll("[\\[\\]:*?/\\\\]", "-");
        return cleaned.length() <= 31 ? cleaned : cleaned.substring(0, 31);
    }
}
