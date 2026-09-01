package com.snrm.dataimport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads one canonical sheet from a CSV file, with the delimiter auto-detection.
 *
 * <p><strong>Which sheet a file is.</strong> Its filename, through {@link ImportSheet#ofName} —
 * {@code nodes.csv}, {@code Node Products.CSV}. A CSV has no other identity, and the alternative
 * (asking the user to label each upload) would put a step in front of the wizard's first step for the
 * common case where the files are already named after the schema. A file whose name matches nothing is
 * returned as no tables at all and reported as unrecognised.
 *
 * <p><strong>Delimiter detection.</strong> European spreadsheet locales export with {@code ;} and
 * some pipelines with a tab, so the comma cannot be assumed — but a delimiter guessed wrong is
 * catastrophic and silent: every row becomes one field, the header matches nothing, and the user gets
 * a wall of "missing required column" for a file that is perfectly well formed. So detection is
 * decided by {@link #detectDelimiter} on evidence rather than on frequency alone: a candidate must
 * split the header into more than one field <em>and</em> split the first data rows into the same
 * number of fields. Consistency is what distinguishes a real delimiter from a character that merely
 * occurs inside values — a {@code ;} in one description field does not make it a delimiter, because
 * the other rows would then disagree about the field count.
 *
 * <p>Quoting, embedded newlines and escaped quotes are Commons CSV's business, not ours.
 */
@Component
@Order(10)
public class CsvAdapter implements DataSourceAdapter {

    /** Candidates in the order they are tried; ties go to the earlier one. */
    private static final char[] CANDIDATE_DELIMITERS = {',', ';', '\t', '|'};

    /** How many data rows the field-count agreement is checked over. Enough to be sure, cheap. */
    private static final int SNIFF_ROWS = 5;

    /** The UTF-8 byte-order mark Excel writes; invisible, and it corrupts the first header. */
    private static final String BOM = "﻿";

    @Override
    public boolean supports(UploadedFile file) {
        String extension = file.extension();
        if (extension.equals("csv") || extension.equals("tsv") || extension.equals("txt")) {
            return true;
        }
        // No extension at all: fall back to the declared type. A file called "nodes" with a text
        // content type is a CSV that lost its suffix somewhere, which is common enough on Windows.
        return extension.isEmpty() && file.contentType() != null
                && file.contentType().startsWith("text/");
    }

    @Override
    public List<SourceTable> read(UploadedFile file) throws IOException {
        Optional<ImportSheet> sheet = ImportSheet.ofName(file.filename());
        if (sheet.isEmpty()) {
            return List.of();
        }

        char delimiter = detectDelimiter(file);
        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setDelimiter(delimiter)
                .setIgnoreSurroundingSpaces(true)
                // Blank lines are NOT ignored, deliberately: with them counted, a record's number is
                // its physical line number, which is what a diagnostic has to quote for the user to
                // find the row in their editor. Skipping them here instead costs one check.
                .setIgnoreEmptyLines(false)
                .setTrim(false)   // stage 1 trims per column; a trailing space in a name is worth seeing
                .build();

        List<String> headers = new ArrayList<>();
        List<SourceTable.SourceRow> rows = new ArrayList<>();

        try (Reader reader = reader(file); CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                List<String> cells = new ArrayList<>(record.size());
                for (int i = 0; i < record.size(); i++) {
                    cells.add(record.get(i));
                }
                SourceTable.SourceRow row = new SourceTable.SourceRow(lineOf(record, parser), cells);
                if (row.isBlank()) {
                    continue; // a spacer row, before or after the header; not an error
                }
                if (headers.isEmpty()) {
                    // The first non-blank line is the header. Its first cell may carry the BOM.
                    if (!cells.isEmpty()) {
                        cells.set(0, stripBom(cells.get(0)));
                    }
                    headers.addAll(cells);
                    continue;
                }
                rows.add(row);
            }
        }

        String origin = "CSV (delimiter '%s')".formatted(printable(delimiter));
        return List.of(new SourceTable(sheet.get(), file.filename(), origin, headers, rows));
    }

    @Override
    public String formatName() {
        return "CSV";
    }

    /**
     * The physical line a record sits on, as the user's editor numbers them.
     *
     * <p>With empty lines counted, the record number <em>is</em> the line number for ordinary
     * single-line rows — and it stays right for the last row of a file with no trailing newline, where
     * the parser's own line counter is one short because it counts terminators. The parser's counter
     * is nonetheless the larger and more useful answer when a quoted field spans lines, so the two are
     * reconciled by taking the greater: the end line of a multi-line record, the exact line otherwise.
     */
    private static int lineOf(CSVRecord record, CSVParser parser) {
        return (int) Math.max(record.getRecordNumber(), parser.getCurrentLineNumber());
    }

    /**
     * The delimiter this file is written with.
     *
     * <p>Scores each candidate over the header and the first {@link #SNIFF_ROWS} data lines: a
     * candidate is viable only if the header splits into at least two fields and every sampled data
     * line splits into that same count. Among the viable ones the highest field count wins, since a
     * file that genuinely has eight columns is better explained by the character that finds eight than
     * by one that finds two. Nothing viable falls back to a comma — the canonical form —
     * and the resulting "missing required column" diagnostics then name the real problem.
     */
    char detectDelimiter(UploadedFile file) throws IOException {
        List<String> lines = firstLines(file, SNIFF_ROWS + 1);
        if (lines.isEmpty()) {
            return ',';
        }
        char best = ',';
        int bestFields = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            int headerFields = countFields(lines.get(0), candidate);
            if (headerFields < 2) {
                continue;
            }
            boolean consistent = true;
            for (int i = 1; i < lines.size(); i++) {
                if (countFields(lines.get(i), candidate) != headerFields) {
                    consistent = false;
                    break;
                }
            }
            if (consistent && headerFields > bestFields) {
                best = candidate;
                bestFields = headerFields;
            }
        }
        return best;
    }

    /**
     * Fields a line splits into, respecting double quotes.
     *
     * <p>Hand-counted rather than run through Commons CSV: the sniff has to survive lines that are
     * malformed under the wrong delimiter, and the parser would rightly throw on some of those. This
     * only has to be good enough to compare candidates.
     */
    private static int countFields(String line, char delimiter) {
        int fields = 1;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                quoted = !quoted;
            } else if (character == delimiter && !quoted) {
                fields++;
            }
        }
        return fields;
    }

    /** The first {@code limit} non-blank lines, for the sniff. */
    private static List<String> firstLines(UploadedFile file, int limit) throws IOException {
        List<String> lines = new ArrayList<>(limit);
        try (BufferedReader reader = new BufferedReader(reader(file))) {
            String line;
            while (lines.size() < limit && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(stripBom(line));
                }
            }
        }
        return lines;
    }

    private static Reader reader(UploadedFile file) {
        // UTF-8 unconditionally. Guessing an encoding is a worse failure mode than mojibake in a
        // region name: an import is validated and previewed before it is committed, so a wrong
        // character is visible in the wizard, whereas a mis-sniffed encoding could change a number.
        return new InputStreamReader(file.open(), StandardCharsets.UTF_8);
    }

    private static String stripBom(String value) {
        return value != null && value.startsWith(BOM) ? value.substring(1) : value;
    }

    private static String printable(char delimiter) {
        return delimiter == '\t' ? "\\t" : String.valueOf(delimiter);
    }
}
