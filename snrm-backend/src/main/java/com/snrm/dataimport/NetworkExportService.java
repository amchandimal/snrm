package com.snrm.dataimport;

import com.snrm.common.Rate;
import com.snrm.network.Link;
import com.snrm.network.LinkRepository;
import com.snrm.network.Network;
import com.snrm.network.NetworkLookup;
import com.snrm.network.Node;
import com.snrm.network.NodeProduct;
import com.snrm.network.NodeProductRepository;
import com.snrm.network.NodeRepository;
import com.snrm.network.Product;
import com.snrm.network.ProductRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a network back out in the canonical schema.
 *
 * <blockquote>"the same canonical schema is used by the export function, making round-tripping (export →
 * edit in Excel → re-import as a new variant) a supported workflow. Export writes the units the user
 * chose, so a round trip is unit-preserving."</blockquote>
 *
 * <p><strong>Why the round trip is structural rather than a promise.</strong> Every header and every
 * column order here comes from {@link ImportSchema}, the same table the importer validates against. There
 * is no second list of column names to fall out of step, so a column added to the schema appears in the
 * export and is understood by the import in one change. The columns are also written in schema order,
 * which is what makes a diff between an export and a re-export readable.
 *
 * <p><strong>Units are always written, never folded away.</strong> A lead time held as 36 HOUR is written
 * as {@code lead_time_value=36}, {@code lead_time_unit=HOUR} — not as 1.5 days, and not as a bare 36.
 * Restating it in the period's unit would lose the user's phrasing, which is kept deliberately;
 * dropping the unit column would make the re-import read the number in the period unit and quietly
 * rescale it. The literal enum name is written rather than an abbreviation because it is unambiguous, and
 * the importer accepts both.
 *
 * <p><strong>Three formats, one set of rows.</strong> {@code xlsx} produces one workbook with the five
 * sheets — through {@link SXSSFWorkbook}, POI's streaming writer, so a 1,000-node network does not build
 * a document object per cell. {@code csv} produces a zip of the five files, because a CSV export of a
 * five-table schema is five files and a browser can only be handed one. {@code xml} produces the
 * interchange document. All three are rendered from {@link #rowsOf}, so no format can
 * disagree with another about content — which is what makes "export as XML, re-import, export as CSV"
 * describe the same network three times.
 */
@Service
public class NetworkExportService {

    /** What an export is called: {@code Baseline-v1.xlsx}. */
    private static final String FILENAME_PATTERN = "%s-v%d.%s";

    private final NetworkLookup lookup;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final NodeProductRepository nodeProducts;
    private final ProductRepository products;

    NetworkExportService(NetworkLookup lookup, NodeRepository nodes, LinkRepository links,
            NodeProductRepository nodeProducts, ProductRepository products) {
        this.lookup = lookup;
        this.nodes = nodes;
        this.links = links;
        this.nodeProducts = nodeProducts;
        this.products = products;
    }

    /** The two forms a network can be asked for — the two forms the import accepts on the way in. */
    public enum Format {

        /** One workbook, five sheets — the form that re-imports in a single upload. */
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

        /** A zip of the five CSV files, each named after its sheet. */
        CSV("zip", "application/zip"),

        /**
         * The full-fidelity interchange document — one self-describing file carrying
         * everything the network holds, canvas layout included.
         */
        XML("xml", "application/xml");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }

        /**
         * The format a {@code ?format=} value names, case-insensitively, accepting {@code zip} as a
         * synonym for {@code csv}.
         *
         * <p>Parsed here rather than left to Spring's enum converter, which is case-sensitive: a query
         * parameter is typed by a person, and {@code ?format=xlsx} failing where {@code ?format=XLSX}
         * works is a 400 nobody can read the reason for.
         *
         * @throws IllegalArgumentException rendered as a 400 by the global handler
         */
        public static Format of(String value) {
            String token = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (token) {
                case "", "xlsx", "excel", "workbook" -> XLSX;
                case "csv", "zip" -> CSV;
                case "xml" -> XML;
                default -> throw new IllegalArgumentException(
                        "format must be 'xlsx' for one workbook, 'csv' for a zip of the five CSV "
                                + "files, or 'xml' for the interchange document, was '"
                                + value + "'");
            };
        }
    }

    /** An export, ready to be handed to the client. */
    public record Export(String filename, String contentType, byte[] content) {
    }

    /**
     * Exports a network.
     *
     * @param networkId the network to write
     * @param ownerId   the calling researcher
     * @param format    workbook or zipped CSV set
     * @throws jakarta.persistence.EntityNotFoundException if the network is not the caller's
     */
    @Transactional(readOnly = true)
    public Export export(long networkId, long ownerId, Format format) {
        Network network = lookup.requireNetwork(networkId, ownerId);
        Map<ImportSheet, List<List<String>>> sheets = rowsOf(network);

        byte[] content = switch (format) {
            case XLSX -> workbook(sheets);
            case CSV -> zippedCsv(sheets);
            case XML -> xml(sheets);
        };
        String filename = FILENAME_PATTERN.formatted(safeName(network.getName()),
                network.getVersion(), format.extension);
        return new Export(filename, format.contentType(), content);
    }

    // ------------------------------------------------------------------- the content

    /**
     * Every sheet's rows as strings, in schema order — the single source both formats are written from.
     *
     * <p>Read through the repositories rather than through the entity's collections so the query count
     * is fixed at four regardless of network size, which is the same choice {@code NetworkGraphFactory}
     * makes for the snapshot.
     */
    private Map<ImportSheet, List<List<String>>> rowsOf(Network network) {
        Map<ImportSheet, List<List<String>>> sheets = new LinkedHashMap<>();

        sheets.put(ImportSheet.NETWORK_META, List.of(List.of(
                number(network.getPeriodLength().getValue()),
                network.getPeriodLength().getUnit().name(),
                String.valueOf(network.getHorizonPeriods()),
                network.getRoundingPolicy().name(),
                network.getName())));

        List<Node> nodeRows = nodes.findByNetworkId(network.getId());
        List<List<String>> nodeLines = new ArrayList<>(nodeRows.size());
        for (Node node : nodeRows) {
            nodeLines.add(List.of(
                    node.getName(),
                    node.getType().name(),
                    rateValue(node.getCapacity()),
                    unitOf(node.getCapacity()),
                    number(node.getProcessingTime().getValue()),
                    node.getProcessingTime().getUnit().name(),
                    number(node.getFixedCost()),
                    number(node.getVarCost()),
                    number(node.getFailureProb()),
                    text(node.getRegion()),
                    number(node.getLat()),
                    number(node.getLng()),
                    // Canvas layout, so a round trip returns the network the way it was arranged.
                    // Empty for a node the editor has never positioned, which re-imports as
                    // "no layout" and is auto-laid-out rather than being pinned to the origin.
                    number(node.getPosX()),
                    number(node.getPosY()),
                    // FR-30, on the same terms as the layout above: annotation is modelling work.
                    // The flag is written on every row, even where there is no caption, for the
                    // reason unitOf writes a unit next to an empty capacity — both halves of the
                    // pair stay well-formed, so typing a caption into the exported file and
                    // re-importing does not need a flag invented for it.
                    text(node.getCaption()),
                    flag(node.isCaptionVisible())));
        }
        sheets.put(ImportSheet.NODES, nodeLines);

        List<Link> linkRows = links.findByNetworkId(network.getId());
        List<List<String>> linkLines = new ArrayList<>(linkRows.size());
        for (Link link : linkRows) {
            linkLines.add(List.of(
                    link.getSourceNode().getName(),
                    link.getTargetNode().getName(),
                    number(link.getLeadTime().getValue()),
                    link.getLeadTime().getUnit().name(),
                    rateValue(link.getCapacity()),
                    unitOf(link.getCapacity()),
                    number(link.getUnitCost()),
                    number(link.getFailureProb()),
                    text(link.getCaption()),
                    flag(link.isCaptionVisible())));
        }
        sheets.put(ImportSheet.LINKS, linkLines);

        // Only the products this network actually uses. Products are project-scoped, and an
        // export that carried the whole catalogue would re-import products this network never referenced
        // into whatever project it landed in.
        List<NodeProduct> nodeProductRows = nodeProducts.findByNetworkId(network.getId());
        Set<Long> referenced = new LinkedHashSet<>();
        for (NodeProduct row : nodeProductRows) {
            referenced.add(row.getId().getProductId());
        }
        List<List<String>> productLines = new ArrayList<>(referenced.size());
        for (Product product : products.findByProjectIdOrderByNameAsc(
                network.getProject().getId())) {
            if (referenced.contains(product.getId())) {
                productLines.add(List.of(product.getName(), number(product.getUnitValue())));
            }
        }
        sheets.put(ImportSheet.PRODUCTS, productLines);

        List<List<String>> nodeProductLines = new ArrayList<>(nodeProductRows.size());
        for (NodeProduct row : nodeProductRows) {
            nodeProductLines.add(List.of(
                    row.getNode().getName(),
                    row.getProduct().getName(),
                    rateValue(row.getDemand()),
                    unitOf(row.getDemand()),
                    number(row.getInitialInventory()),
                    number(row.getSafetyStock()),
                    rateValue(row.getHoldingCost()),
                    unitOf(row.getHoldingCost())));
        }
        sheets.put(ImportSheet.NODE_PRODUCTS, nodeProductLines);

        return sheets;
    }

    // ------------------------------------------------------------------------- xlsx

    private byte[] workbook(Map<ImportSheet, List<List<String>>> sheets) {
        // Keep 100 rows in memory per sheet and flush the rest to a temp file; the workbook is disposed
        // afterwards so nothing is left behind (SXSSFWorkbook's own contract).
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            try {
                for (ImportSheet sheet : ImportSheet.declarationOrder()) {
                    Sheet target = workbook.createSheet(sheet.canonicalName());
                    write(target.createRow(0), ImportSchema.headersOf(sheet), headerStyle);
                    int rowIndex = 1;
                    for (List<String> row : sheets.getOrDefault(sheet, List.of())) {
                        write(target.createRow(rowIndex++), row, null);
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

    private static void write(Row row, List<String> values, CellStyle style) {
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values.get(i));
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    // -------------------------------------------------------------------------- csv

    private byte[] zippedCsv(Map<ImportSheet, List<List<String>>> sheets) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (ImportSheet sheet : ImportSheet.declarationOrder()) {
                zip.putNextEntry(new ZipEntry(sheet.canonicalName() + ".csv"));
                zip.write(csv(sheet, sheets.getOrDefault(sheet, List.of())));
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write the CSV archive", failure);
        }
    }

    private static byte[] csv(ImportSheet sheet, List<List<String>> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Comma-delimited and UTF-8: the canonical form, and what CsvAdapter's delimiter
        // detection will find again without having to guess.
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord(ImportSchema.headersOf(sheet));
            for (List<String> row : rows) {
                printer.printRecord(row);
            }
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------- xml

    /**
     * The interchange document, written from the same rows the tabular formats use.
     *
     * <p>Driven entirely by {@link XmlSchema}, which {@link XmlAdapter} also reads through — so the
     * document this produces is the document that parses, and a column added to
     * {@link ImportSchema} plus a name added to {@link XmlSchema} appears in all three formats at once.
     * Nothing here knows what a node is; it maps columns to attributes and unit pairs to child
     * elements.
     *
     * <p>An empty value is written as no attribute at all rather than as {@code attr=""}: absent and
     * empty mean the same thing on the way back in, and omitting keeps the document readable
     * — a node with no region should not carry {@code region=""}.
     */
    private byte[] xml(Map<ImportSheet, List<List<String>>> sheets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            try {
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
                newline(writer);
                writer.writeStartElement(XmlSchema.NETWORK);
                writer.writeAttribute(XmlSchema.ATTR_SCHEMA_VERSION,
                        String.valueOf(XmlSchema.SCHEMA_VERSION));

                for (ImportSheet sheet : ImportSheet.declarationOrder()) {
                    writeSheet(writer, sheet, sheets.getOrDefault(sheet, List.of()));
                }

                newline(writer);
                writer.writeEndElement();
                writer.writeEndDocument();
                writer.flush();
            } finally {
                writer.close();
            }
        } catch (XMLStreamException failure) {
            // Writing to a byte array cannot fail for any reason the caller can act on.
            throw new IllegalStateException("Could not write the XML document", failure);
        }
        return out.toByteArray();
    }

    private static void writeSheet(XMLStreamWriter writer, ImportSheet sheet,
            List<List<String>> rows) throws XMLStreamException {

        if (rows.isEmpty()) {
            return;
        }
        List<String> columns = ImportSchema.headersOf(sheet);
        String container = XmlSchema.containerOf(sheet);
        if (container != null) {
            newline(writer);
            writer.writeStartElement(container);
        }
        for (List<String> row : rows) {
            newline(writer);
            writeRow(writer, sheet, columns, row);
        }
        if (container != null) {
            newline(writer);
            writer.writeEndElement();
        }
    }

    private static void writeRow(XMLStreamWriter writer, ImportSheet sheet, List<String> columns,
            List<String> row) throws XMLStreamException {

        XmlSchema.Quantity[] quantities = XmlSchema.quantitiesOf(sheet);
        boolean hasChildren = false;
        for (XmlSchema.Quantity quantity : quantities) {
            hasChildren |= !valueOf(columns, row, quantity.valueColumn()).isEmpty()
                    || !valueOf(columns, row, quantity.unitColumn()).isEmpty();
        }

        if (hasChildren) {
            writer.writeStartElement(XmlSchema.rowElementOf(sheet));
        } else {
            writer.writeEmptyElement(XmlSchema.rowElementOf(sheet));
        }
        for (String[] attribute : XmlSchema.attributesOf(sheet)) {
            String value = valueOf(columns, row, attribute[1]);
            if (!value.isEmpty()) {
                writer.writeAttribute(attribute[0], value);
            }
        }
        for (XmlSchema.Quantity quantity : quantities) {
            String value = valueOf(columns, row, quantity.valueColumn());
            String unit = valueOf(columns, row, quantity.unitColumn());
            if (value.isEmpty() && unit.isEmpty()) {
                continue;
            }
            writer.writeEmptyElement(quantity.element());
            if (!value.isEmpty()) {
                writer.writeAttribute(XmlSchema.ATTR_VALUE, value);
            }
            if (!unit.isEmpty()) {
                // Written even when the value is empty: an unconstrained capacity still has a unit, and
                // keeping the pair well-formed means giving it a number later needs no unit invented.
                writer.writeAttribute(quantity.unitAttribute(), unit);
            }
        }
        if (hasChildren) {
            writer.writeEndElement();
        }
    }

    /** A row's value for a canonical column, by the column's position in the schema. */
    private static String valueOf(List<String> columns, List<String> row, String column) {
        int index = columns.indexOf(column);
        return index < 0 || index >= row.size() ? "" : row.get(index);
    }

    /**
     * A line break between elements.
     *
     * <p>StAX writes no whitespace of its own, and a one-line document is unreadable and undiffable —
     * which defeats half the point of an archival format. Indentation is deliberately not
     * attempted: it would mean tracking depth for cosmetic gain, and every editor can format XML.
     */
    private static void newline(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeCharacters("\n");
    }

    // ---------------------------------------------------------------------- rendering

    /**
     * A number as short as it can be written without losing anything: {@code 6} rather than
     * {@code 6.0}, {@code 1.5} as {@code 1.5}.
     *
     * <p>Not cosmetic. A file full of {@code 6.0} where the user wrote {@code 6} makes a re-export diff
     * unreadable, and the round trip is only useful if a user can see what their edit changed.
     * {@link Locale#ROOT} because a decimal comma would collide with the delimiter and be re-read as two
     * columns.
     */
    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%s", value);
    }

    /** An optional number: empty rather than {@code null} or {@code 0}, which mean different things. */
    private static String number(Double value) {
        return value == null ? "" : number(value.doubleValue());
    }

    /** A rate's value, or empty for an unconstrained capacity — which is what the import reads back. */
    private static String rateValue(Rate rate) {
        return rate == null ? "" : number(rate.getValue());
    }

    /**
     * A rate's unit, always written even when the value is empty.
     *
     * <p>An unconstrained capacity still has a unit in the model ({@link Rate}), and writing it keeps the
     * pair well-formed for the round trip: giving the capacity a number in Excel and re-importing then
     * does not need a unit invented for it.
     */
    private static String unitOf(Rate rate) {
        return rate == null || rate.getTimeUnit() == null ? "" : rate.getTimeUnit().name();
    }

    /** An optional string: empty for absent, which is how the import reads it back. */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * A yes/no flag, written as the literal the importer's token table names first (FR-30).
     *
     * <p>{@code true} rather than {@code 1} or {@code yes} for the reason the unit columns are
     * written as the enum name: it is unambiguous to a reader, and {@link UnitTokens#flag} accepts
     * every spelling anyway, so nothing is lost by picking the plainest one.
     */
    private static String flag(boolean value) {
        return Boolean.toString(value);
    }

    /** A filename Windows and the {@code Content-Disposition} header will both accept. */
    private static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return cleaned.isEmpty() ? "network" : cleaned;
    }
}
