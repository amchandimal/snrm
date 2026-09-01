package com.snrm.dataimport;

import com.snrm.dataimport.XmlSchema.Quantity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the XML interchange document into the same tables the other two adapters produce.
 *
 * <p><strong>Why it produces rows rather than a network.</strong> The obvious implementation of an XML
 * importer is a direct one: parse the document into staged entities and be done. It would also be a
 * second validator. Everything stage 1 checks — types, ranges, unit tokens, uniqueness,
 * referential integrity — applies identically to XML, and a separate path would mean an XML import
 * reporting different codes, or missing checks, for the same mistake. So this adapter flattens each row
 * element into the canonical columns of {@link ImportSchema} and hands them over as
 * {@link SourceTable}s. Everything downstream is unchanged, and an XML import reports exactly what the
 * equivalent spreadsheet would.
 *
 * <p><strong>Unit-bearing quantities are child elements</strong> ({@code <leadTime value="3"
 * unit="DAY"/>}), and are flattened into the two columns the tabular schema uses. An absent child means
 * an absent value, which is the same as an empty cell: the field's documented default, in the network's
 * period unit.
 *
 * <p><strong>Line numbers are the document's own.</strong> A diagnostic against a node row quotes the
 * line the {@code <node>} element starts on, so it points where a text editor will.
 *
 * <p>The parser is configured to refuse external entities and DTDs. An import is a file from outside
 * the tool, and XML's entity mechanism is a well-known way to make a parser read local files or make
 * network requests on the attacker's behalf.
 */
@Component
@Order(30)
public class XmlAdapter implements DataSourceAdapter {

    @Override
    public boolean supports(UploadedFile file) {
        if (file.extension().equals("xml")) {
            return true;
        }
        return file.extension().isEmpty() && file.contentType() != null
                && (file.contentType().contains("xml"));
    }

    @Override
    public List<SourceTable> read(UploadedFile file) throws IOException {
        Map<ImportSheet, Rows> collected = new EnumMap<>(ImportSheet.class);
        for (ImportSheet sheet : ImportSheet.declarationOrder()) {
            collected.put(sheet, new Rows());
        }

        try (InputStream stream = file.open()) {
            XMLStreamReader reader = inputFactory().createXMLStreamReader(stream);
            try {
                readDocument(reader, file, collected);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException malformed) {
            throw new IOException(("%s is not well-formed XML: %s")
                    .formatted(file.filename(), malformed.getMessage()), malformed);
        }

        List<SourceTable> tables = new ArrayList<>();
        for (ImportSheet sheet : ImportSheet.declarationOrder()) {
            // An absent section is an absent sheet, which is what the defaults are for.
            collected.get(sheet).toTable(sheet, file.filename()).ifPresent(tables::add);
        }
        return tables;
    }

    @Override
    public String formatName() {
        return "XML";
    }

    // ------------------------------------------------------------------- the document

    private void readDocument(XMLStreamReader reader, UploadedFile file,
            Map<ImportSheet, Rows> collected) throws XMLStreamException, IOException {

        boolean rootSeen = false;
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String element = reader.getLocalName();
            if (!rootSeen) {
                if (!XmlSchema.NETWORK.equals(element)) {
                    throw new IOException(("%s is XML but not an SNRM network document: the root "
                            + "element is <%s>, expected <%s>.")
                            .formatted(file.filename(), element, XmlSchema.NETWORK));
                }
                requireSchemaVersion(reader, file);
                rootSeen = true;
                continue;
            }
            ImportSheet sheet = sheetOfRowElement(element);
            if (sheet != null) {
                collected.get(sheet).add(readRow(reader, sheet));
            }
            // Container elements (<nodes>, <links>, …) are simply walked through: the row elements
            // inside them are what carries data, and requiring the wrapper would reject a document
            // that is otherwise unambiguous.
        }

        if (!rootSeen) {
            throw new IOException("%s contains no <%s> element."
                    .formatted(file.filename(), XmlSchema.NETWORK));
        }
    }

    /**
     * Refuses a document whose {@code schemaVersion} this build does not understand.
     *
     * <p>Stated rather than assumed: a document from a future version may use the same element names
     * for different things, and parsing it optimistically would produce a network that is wrong in ways
     * validation cannot see. An absent attribute is treated as version 1, so a hand-written document
     * does not need it.
     */
    private static void requireSchemaVersion(XMLStreamReader reader, UploadedFile file)
            throws IOException {
        String declared = reader.getAttributeValue(null, XmlSchema.ATTR_SCHEMA_VERSION);
        if (declared == null || declared.isBlank()) {
            return;
        }
        try {
            int version = Integer.parseInt(declared.trim());
            if (version == XmlSchema.SCHEMA_VERSION) {
                return;
            }
            throw new IOException(("%s declares schemaVersion %d; this version of SNRM reads "
                    + "schemaVersion %d.")
                    .formatted(file.filename(), version, XmlSchema.SCHEMA_VERSION));
        } catch (NumberFormatException notANumber) {
            throw new IOException("%s has a non-numeric schemaVersion '%s'."
                    .formatted(file.filename(), declared));
        }
    }

    /**
     * One row element and its unit-bearing children, flattened into canonical columns.
     *
     * <p>The reader is positioned on the row's START_ELEMENT and is left on its END_ELEMENT, so the
     * caller's walk continues correctly whether or not the row had children.
     */
    private static Row readRow(XMLStreamReader reader, ImportSheet sheet) throws XMLStreamException {
        int line = reader.getLocation().getLineNumber();
        String rowElement = reader.getLocalName();
        Map<String, String> values = new LinkedHashMap<>();

        for (String[] attribute : XmlSchema.attributesOf(sheet)) {
            String value = reader.getAttributeValue(null, attribute[0]);
            if (value != null) {
                values.put(attribute[1], value);
            }
        }

        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                readQuantity(reader, sheet, values);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (depth == 0 && !rowElement.equals(reader.getLocalName())) {
                    // Cannot happen in well-formed XML; the parser would have thrown first.
                    break;
                }
            }
        }
        return new Row(line, values);
    }

    /** A {@code <leadTime value="3" unit="DAY"/>} child, as its two canonical columns. */
    private static void readQuantity(XMLStreamReader reader, ImportSheet sheet,
            Map<String, String> values) {
        String element = reader.getLocalName();
        for (Quantity quantity : XmlSchema.quantitiesOf(sheet)) {
            if (!quantity.element().equals(element)) {
                continue;
            }
            String value = reader.getAttributeValue(null, XmlSchema.ATTR_VALUE);
            String unit = reader.getAttributeValue(null, quantity.unitAttribute());
            if (value != null) {
                values.put(quantity.valueColumn(), value);
            }
            if (unit != null) {
                values.put(quantity.unitColumn(), unit);
            }
            return;
        }
    }

    private static ImportSheet sheetOfRowElement(String element) {
        for (ImportSheet sheet : ImportSheet.declarationOrder()) {
            if (XmlSchema.rowElementOf(sheet).equals(element)) {
                return sheet;
            }
        }
        return null;
    }

    /**
     * A parser that will not fetch anything.
     *
     * <p>DTDs and external entities are disabled outright: an import is a file from outside the tool,
     * and those two features are how an XML parser is talked into reading local files or issuing
     * network requests on behalf of whoever wrote the document. Nothing needs either.
     * Coalescing is on so a value split across a CDATA boundary arrives as one string.
     */
    private static XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    // -------------------------------------------------------------------- accumulation

    /** One parsed row element: the line it started on, and its canonical column values. */
    private record Row(int line, Map<String, String> values) {
    }

    /**
     * The rows of one sheet, accumulating the union of the columns they actually used.
     *
     * <p>XML is sparse where a spreadsheet is rectangular — an attribute that is not applicable is
     * simply absent — so the header is discovered rather than declared. Building it as a union and
     * padding every row to it at the end is what turns a sparse document back into the rectangular
     * table the validator expects, without inventing a column that no element used.
     */
    private static final class Rows {

        private final List<String> headers = new ArrayList<>();
        private final List<Row> pending = new ArrayList<>();

        void add(Row row) {
            pending.add(row);
            for (String column : row.values().keySet()) {
                if (!headers.contains(column)) {
                    headers.add(column);
                }
            }
        }

        /** The rectangular table, or empty when the document had no element of this kind. */
        Optional<SourceTable> toTable(ImportSheet sheet, String sourceName) {
            if (pending.isEmpty()) {
                return Optional.empty();
            }
            List<SourceTable.SourceRow> rows = new ArrayList<>(pending.size());
            for (Row row : pending) {
                List<String> cells = new ArrayList<>(headers.size());
                for (String column : headers) {
                    cells.add(row.values().getOrDefault(column, ""));
                }
                rows.add(new SourceTable.SourceRow(row.line(), cells));
            }
            return Optional.of(new SourceTable(sheet, sourceName, "XML", headers, rows));
        }
    }
}
