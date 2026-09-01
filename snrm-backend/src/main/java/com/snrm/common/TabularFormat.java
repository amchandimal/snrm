package com.snrm.common;

import java.util.Locale;

/**
 * The two forms a table can be downloaded in — the {@code ?format=} value of the results and
 * comparison exports.
 *
 * <p>Two, where {@code NetworkExportService.Format} has three. The missing one is XML, and its
 * absence is the point: the XML export is an interchange document for a <em>network</em>, so that a
 * network can be archived whole and re-imported. A metric table has no interchange format and no way
 * back in — offering `?format=xml` here would produce a document nothing can read, which is worse
 * than not offering it.
 *
 * <p>Parsed leniently, like its network counterpart: a query parameter is typed by a person, and
 * {@code ?format=xlsx} failing where {@code ?format=XLSX} works is a 400 nobody can read the reason
 * for.
 */
public enum TabularFormat {

    /** One workbook, one sheet per table — the form to open and read. */
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    /** A zip of one CSV per table — the form to diff, script over, or read into R. */
    CSV("zip", "application/zip");

    private final String extension;
    private final String contentType;

    TabularFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** The file extension an export of this format is named with. */
    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /**
     * The format a {@code ?format=} value names, case-insensitively, accepting {@code zip} as a
     * synonym for {@code csv} and an empty value as the default.
     *
     * @throws IllegalArgumentException rendered as a 400 by the global handler
     */
    public static TabularFormat of(String value) {
        String token = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (token) {
            case "", "xlsx", "excel", "workbook" -> XLSX;
            case "csv", "zip" -> CSV;
            default -> throw new IllegalArgumentException(
                    "format must be 'xlsx' for one workbook or 'csv' for a zip of the tables, was '"
                            + value + "'");
        };
    }
}
