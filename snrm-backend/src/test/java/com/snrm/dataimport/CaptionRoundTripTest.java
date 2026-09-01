package com.snrm.dataimport;

import com.snrm.common.Rate;
import com.snrm.common.TimeUnit;
import com.snrm.network.Link;
import com.snrm.network.Network;
import com.snrm.network.NetworkLookup;
import com.snrm.network.Node;
import com.snrm.network.NodeType;
import com.snrm.network.LinkRepository;
import com.snrm.network.NodeProductRepository;
import com.snrm.network.NodeRepository;
import com.snrm.network.ProductRepository;
import com.snrm.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A caption survives being written out and read back in, in all three formats (FR-30).
 *
 * <p><strong>What this actually pins.</strong> Not that a column exists — that a column exists
 * <em>in the one list every writer emits from</em>. {@link ImportSchema} is the only declaration of
 * canonical column names in the system and {@link NetworkExportService} writes each format from it,
 * so if the caption pair were added to the reader and forgotten in the writer, or added to the
 * tabular schema and forgotten in {@link XmlSchema}, exactly one of the three round trips below
 * would come back empty. That is the failure this class exists to catch — an export that cannot
 * be re-imported.
 *
 * <p><strong>No database and no Docker.</strong> The export service reads five repositories and
 * writes bytes; the repositories are stubbed, because nothing here is a claim about persistence.
 * What is exercised is the whole format layer — POI, Commons CSV and StAX in both directions,
 * through {@link SourceReader}, {@link MappedTable} and {@link RowValidator} — which is where a
 * caption can be dropped. {@code CaptionEditTest} covers the persistence half against a real schema.
 *
 * <p><strong>The archive comes free, and is checked rather than assumed.</strong>
 * {@code ProjectArchiveService} stores each network as the bytes of
 * {@code export(id, owner, Format.XML)} and restores them through {@code ImportService}, so
 * {@link #theXmlDocumentTheArchiveStoresCarriesTheCaptions()} asserts against the same call the
 * archive makes. There is no second network format to keep in step (see
 * {@code archive/package-info.java}).
 */
@DisplayName("Captions through export and back — FR-30")
class CaptionRoundTripTest {

    private static final long NETWORK_ID = 42L;
    private static final long OWNER = 6857L;

    /** A caption with the em dash and the trailing detail a real one has. */
    private static final String DC_CAPTION = "Nordic hub — 3PL operated, 2 shifts";

    /** One that is present and hidden: the state FR-30 exists to make possible. */
    private static final String PLANT_CAPTION = "Single-sourced; 6-week supplier qualification";

    private static final String LINK_CAPTION = "Ocean leg — one carrier, no alternative routing";

    private NetworkExportService exports;
    private SourceReader reader;
    private RowValidator validator;

    @BeforeEach
    void buildTheNetwork() {
        Project project = new Project("FR-30", OWNER);
        Network network = new Network(project, "Captioned", 3, false);

        Node plant = new Node(network, "PLANT-1", NodeType.PLANT);
        plant.setCapacity(Rate.of(400d, TimeUnit.DAY));
        plant.setPosX(300d);
        plant.setPosY(180d);
        plant.setCaption(PLANT_CAPTION);
        plant.setCaptionVisible(false); // written, kept, and deliberately not drawn

        Node dc = new Node(network, "DC-1", NodeType.DC);
        dc.setCapacity(Rate.of(350d, TimeUnit.DAY));
        dc.setRegion("EU-North");
        dc.setPosX(520d);
        dc.setPosY(190d);
        dc.setCaption(DC_CAPTION);

        // A node with no caption at all, so the round trip has to bring back null rather than "".
        Node customer = new Node(network, "CUST-1", NodeType.CUSTOMER);

        Link ocean = new Link(network, plant, dc);
        ocean.setUnitCost(4d);
        ocean.setCaption(LINK_CAPTION);
        ocean.setCaptionVisible(false);

        Link road = new Link(network, dc, customer);
        road.setUnitCost(2d);
        road.setCaption("Overnight road leg");

        exports = exportServiceOver(network, List.of(plant, dc, customer), List.of(ocean, road));
        reader = new SourceReader(List.of(new CsvAdapter(), new XlsxAdapter(), new XmlAdapter()));
        validator = new RowValidator();
    }

    // ------------------------------------------------------------------- the three formats

    @Test
    @DisplayName("XLSX: every caption and every flag comes back")
    void xlsxRoundTrip() {
        assertCaptionsSurvive(reimport(exportAs(NetworkExportService.Format.XLSX)));
    }

    @Test
    @DisplayName("CSV: the same, through the five files of the zip")
    void csvRoundTrip() {
        assertCaptionsSurvive(reimport(unzip(exportAs(NetworkExportService.Format.CSV))));
    }

    @Test
    @DisplayName("XML: the same, through the interchange document — which the archive also rides on")
    void xmlRoundTrip() {
        assertCaptionsSurvive(reimport(exportAs(NetworkExportService.Format.XML)));
    }

    @Test
    @DisplayName("the XML document the archive stores carries the captions as attributes")
    void theXmlDocumentTheArchiveStoresCarriesTheCaptions() {
        // ProjectArchiveService writes exactly this call's bytes into networks/*.xml, and
        // ProjectRestoreService feeds them back through ImportService. Asserting on the document
        // rather than on a restored row is the point: item 4 of this feature is what makes the
        // archive work, and this is the whole of the dependency.
        String document = new String(exports.export(NETWORK_ID, OWNER,
                NetworkExportService.Format.XML).content(), StandardCharsets.UTF_8);

        assertThat(document)
                .contains("caption=\"Nordic hub — 3PL operated, 2 shifts\"")
                .contains("captionVisible=\"true\"")
                .contains("captionVisible=\"false\"")
                .contains("caption=\"Ocean leg — one carrier, no alternative routing\"");

        // And the uncaptioned node carries no caption attribute at all, rather than caption="".
        assertThat(document).contains("<node name=\"CUST-1\"");
        assertThat(document).doesNotContain("caption=\"\"");
    }

    // ------------------------------------------------------------------ the omission rules

    @Test
    @DisplayName("an omitted caption_visible column means VISIBLE, not false")
    void anOmittedFlagColumnMeansVisible() {
        // The rule and the reason V10's column defaults to TRUE: a file carrying only
        // `caption` was written by someone who meant the caption to be seen, and reading the absence
        // as false would import an annotation nobody can find.
        StagedNetwork staged = stage(csv("nodes.csv", """
                name,type,caption
                SUP-1,SUPPLIER,Long-lead supplier
                """));

        assertThat(staged.nodes()).singleElement().satisfies(node -> {
            assertThat(node.caption()).isEqualTo("Long-lead supplier");
            assertThat(node.captionVisible()).isTrue();
        });
    }

    @Test
    @DisplayName("an empty caption_visible cell means VISIBLE too — empty is absent")
    void anEmptyFlagCellMeansVisible() {
        StagedNetwork staged = stage(csv("nodes.csv", """
                name,type,caption,caption_visible
                SUP-1,SUPPLIER,Long-lead supplier,
                PLANT-1,PLANT,Hidden note,false
                """));

        assertThat(staged.nodes()).extracting(StagedNetwork.StagedNode::captionVisible)
                .containsExactly(true, false);
    }

    @Test
    @DisplayName("an omitted captionVisible attribute in the XML means VISIBLE, on nodes and links")
    void anOmittedXmlAttributeMeansVisible() {
        // Omitting an optional attribute means what omitting the corresponding column means: a
        // missing captionVisible is read as visible. A <link> carries caption/captionVisible on
        // the same terms.
        StagedNetwork staged = stage(new UploadedFile("network.xml", "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <snrm-network schemaVersion="1">
                  <nodes>
                    <node name="PLANT-1" type="PLANT" caption="Assembly, two lines"/>
                    <node name="CUST-1" type="CUSTOMER"/>
                  </nodes>
                  <links>
                    <link source="PLANT-1" target="CUST-1" caption="Overnight road leg"/>
                  </links>
                </snrm-network>
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(staged.nodes()).satisfiesExactly(
                plant -> {
                    assertThat(plant.caption()).isEqualTo("Assembly, two lines");
                    assertThat(plant.captionVisible()).isTrue();
                },
                customer -> {
                    assertThat(customer.caption()).isNull();
                    assertThat(customer.captionVisible())
                            .as("no caption at all is still visible — an empty caption draws "
                                    + "nothing, so the flag is unobservable here and true is the "
                                    + "value the column defaults to")
                            .isTrue();
                });
        assertThat(staged.links()).singleElement().satisfies(link -> {
            assertThat(link.caption()).isEqualTo("Overnight road leg");
            assertThat(link.captionVisible()).isTrue();
        });
    }

    @Test
    @DisplayName("an omitted caption is null — never the empty string")
    void anOmittedCaptionIsNull() {
        StagedNetwork staged = stage(csv("nodes.csv", """
                name,type,caption,caption_visible
                SUP-1,SUPPLIER,,false
                """));

        assertThat(staged.nodes()).singleElement().satisfies(node -> {
            assertThat(node.caption()).isNull();
            assertThat(node.captionVisible())
                    .as("a stated flag is honoured even with nothing to show; the file said it")
                    .isFalse();
        });
    }

    // ----------------------------------------------------------------------- the refusals

    @Test
    @DisplayName("201 characters is refused with the line and the column, and the row is dropped")
    void aCaptionOverTwoHundredCharactersIsRefused() {
        Diagnostics diagnostics = new Diagnostics();
        StagedNetwork staged = stage(diagnostics, csv("nodes.csv", """
                name,type,caption
                SUP-1,SUPPLIER,%s
                PLANT-1,PLANT,fine
                """.formatted("c".repeat(201))));

        assertThat(diagnostics.sorted())
                .filteredOn(finding -> ImportCheck.VALUE_TOO_LONG.name().equals(finding.code()))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.column()).isEqualTo("caption");
                    assertThat(finding.line()).isEqualTo(2);
                    assertThat(finding.message()).contains("200");
                });
        assertThat(staged.nodes())
                .as("a row with a bad cell is dropped and the sheet keeps going")
                .extracting(StagedNetwork.StagedNode::name)
                .containsExactly("PLANT-1");
        assertThat(staged.complete()).isFalse();
    }

    @Test
    @DisplayName("exactly 200 characters is accepted — the cap is the column's, not one under it")
    void twoHundredCharactersIsAccepted() {
        Diagnostics diagnostics = new Diagnostics();
        StagedNetwork staged = stage(diagnostics, csv("nodes.csv", """
                name,type,caption
                SUP-1,SUPPLIER,%s
                """.formatted("c".repeat(200))));

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(staged.nodes()).singleElement()
                .satisfies(node -> assertThat(node.caption()).hasSize(200));
    }

    @Test
    @DisplayName("an unreadable flag is an error, never a guess in either direction")
    void anUnreadableFlagIsRefused() {
        // The UnitTokens discipline applied to a second vocabulary: lenient about the spellings a
        // spreadsheet produces, and refusing anything it is not sure of. Defaulting `maybe` to
        // visible would put on screen the one thing the author may have been trying to hide.
        Diagnostics diagnostics = new Diagnostics();
        StagedNetwork staged = stage(diagnostics, csv("nodes.csv", """
                name,type,caption,caption_visible
                SUP-1,SUPPLIER,Long-lead supplier,maybe
                """));

        assertThat(diagnostics.sorted())
                .filteredOn(finding -> ImportCheck.UNKNOWN_ENUM_VALUE.name().equals(finding.code()))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.column()).isEqualTo("caption_visible");
                    assertThat(finding.value()).isEqualTo("maybe");
                    assertThat(finding.message()).contains("true", "false", "yes", "no");
                });
        assertThat(staged.nodes())
                .as("the row is dropped rather than staged with a guessed flag")
                .isEmpty();
    }

    @Test
    @DisplayName("the spellings a spreadsheet actually writes are all accepted")
    void theLenientSpellingsAreAccepted() {
        StagedNetwork staged = stage(csv("nodes.csv", """
                name,type,caption,caption_visible
                A,SUPPLIER,a,TRUE
                B,SUPPLIER,b,False
                C,SUPPLIER,c,yes
                D,SUPPLIER,d,n
                E,SUPPLIER,e,1
                F,SUPPLIER,f,0
                """));

        assertThat(staged.nodes()).extracting(StagedNetwork.StagedNode::captionVisible)
                .containsExactly(true, false, true, false, true, false);
    }

    // ------------------------------------------------------------------------- assertions

    private static void assertCaptionsSurvive(StagedNetwork staged) {
        assertThat(staged.nodes()).as("three nodes went out; three came back").hasSize(3);

        assertThat(staged.nodes()).satisfiesExactlyInAnyOrder(
                plant -> {
                    assertThat(plant.name()).isEqualTo("PLANT-1");
                    assertThat(plant.caption()).isEqualTo(PLANT_CAPTION);
                    assertThat(plant.captionVisible())
                            .as("hidden is not lost: the whole point of the flag (FR-30)")
                            .isFalse();
                },
                dc -> {
                    assertThat(dc.name()).isEqualTo("DC-1");
                    assertThat(dc.caption()).isEqualTo(DC_CAPTION);
                    assertThat(dc.captionVisible()).isTrue();
                },
                customer -> {
                    assertThat(customer.name()).isEqualTo("CUST-1");
                    assertThat(customer.caption())
                            .as("no caption stays no caption — not an empty string")
                            .isNull();
                    assertThat(customer.captionVisible()).isTrue();
                });

        assertThat(staged.links()).satisfiesExactlyInAnyOrder(
                ocean -> {
                    assertThat(ocean.caption()).isEqualTo(LINK_CAPTION);
                    assertThat(ocean.captionVisible()).isFalse();
                },
                road -> {
                    assertThat(road.caption()).isEqualTo("Overnight road leg");
                    assertThat(road.captionVisible()).isTrue();
                });
    }

    // -------------------------------------------------------------------------- fixtures

    /**
     * The export service over an in-memory network.
     *
     * <p>Its constructor is package-private and this test is in the package, which is deliberate on
     * both sides: the service is an internal of {@code dataimport} and its collaborators are the
     * four repositories a network read needs. The products it would join are stubbed empty — this
     * network carries no per-product rows, and nothing about a caption touches them.
     */
    private static NetworkExportService exportServiceOver(Network network, List<Node> nodes,
            List<Link> links) {

        NetworkLookup lookup = mock(NetworkLookup.class);
        NodeRepository nodeRepository = mock(NodeRepository.class);
        LinkRepository linkRepository = mock(LinkRepository.class);
        NodeProductRepository nodeProductRepository = mock(NodeProductRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);

        // any() rather than anyLong(): these entities were never persisted, so their ids are null,
        // which is irrelevant to every claim here and would be the one thing anyLong() refused to
        // match.
        when(lookup.requireNetwork(anyLong(), anyLong())).thenReturn(network);
        when(nodeRepository.findByNetworkId(any())).thenReturn(nodes);
        when(linkRepository.findByNetworkId(any())).thenReturn(links);
        when(nodeProductRepository.findByNetworkId(any())).thenReturn(List.of());
        when(productRepository.findByProjectIdOrderByNameAsc(any())).thenReturn(List.of());

        return new NetworkExportService(lookup, nodeRepository, linkRepository,
                nodeProductRepository, productRepository);
    }

    private UploadedFile exportAs(NetworkExportService.Format format) {
        NetworkExportService.Export export = exports.export(NETWORK_ID, OWNER, format);
        return new UploadedFile(export.filename(), export.contentType(), export.content());
    }

    /** The five CSV files inside the zip the CSV export produces, as separate uploads. */
    private static List<UploadedFile> unzip(UploadedFile archive) {
        List<UploadedFile> files = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.content()),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                zip.transferTo(content);
                files.add(new UploadedFile(entry.getName(), "text/csv", content.toByteArray()));
            }
        } catch (IOException unreachable) {
            throw new UncheckedIOException(unreachable);
        }
        return files;
    }

    private StagedNetwork reimport(UploadedFile file) {
        return stage(file);
    }

    private StagedNetwork reimport(List<UploadedFile> files) {
        return stage(new Diagnostics(), files);
    }

    private StagedNetwork stage(UploadedFile... files) {
        return stage(new Diagnostics(), List.of(files));
    }

    private StagedNetwork stage(Diagnostics diagnostics, UploadedFile... files) {
        return stage(diagnostics, List.of(files));
    }

    /**
     * The importer's read path with no web layer and no database: parse, resolve columns against
     * {@link ImportSchema}, then stage 1. Deliberately not {@code ImportService}, which would need a
     * project to write into — the claim here is about the format layer.
     */
    private StagedNetwork stage(Diagnostics diagnostics, List<UploadedFile> files) {
        Map<ImportSheet, SourceTable> tables = reader.readFiles(files, diagnostics);
        Map<ImportSheet, MappedTable> mapped = new EnumMap<>(ImportSheet.class);
        for (Map.Entry<ImportSheet, SourceTable> entry : tables.entrySet()) {
            mapped.put(entry.getKey(), MappedTable.of(entry.getValue(), Map.of()));
        }
        return validator.stage(mapped, null, Set.of(), diagnostics);
    }

    private static UploadedFile csv(String filename, String content) {
        return new UploadedFile(filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
