package com.snrm.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.snrm.archive.ProjectArchiveService.Archive;
import com.snrm.archive.ArchiveReport.Finding;
import com.snrm.common.DurationAmount;
import com.snrm.common.Rate;
import com.snrm.common.TimeUnit;
import com.snrm.metrics.MetricResult;
import com.snrm.metrics.MetricResultRepository;
import com.snrm.metrics.MetricScope;
import com.snrm.network.ConfigurationVariant;
import com.snrm.network.ConfigurationVariantRepository;
import com.snrm.network.Link;
import com.snrm.network.LinkRepository;
import com.snrm.network.Network;
import com.snrm.network.NetworkRepository;
import com.snrm.network.Node;
import com.snrm.network.NodeProduct;
import com.snrm.network.NodeProductRepository;
import com.snrm.network.NodeRepository;
import com.snrm.network.NodeType;
import com.snrm.network.Product;
import com.snrm.network.ProductRepository;
import com.snrm.network.VariantOrigin;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import com.snrm.scenario.DisruptionEvent;
import com.snrm.scenario.DisruptionScenario;
import com.snrm.scenario.DisruptionScenarioRepository;
import com.snrm.scenario.DisruptionTargetType;
import com.snrm.scenario.RecoveryProfileType;
import com.snrm.simulation.RunTimeseries;
import com.snrm.simulation.RunTimeseriesRepository;
import com.snrm.simulation.SimulationParams;
import com.snrm.simulation.SimulationRequest;
import com.snrm.simulation.SimulationRun;
import com.snrm.simulation.SimulationRunRepository;
import com.snrm.simulation.SimulationService;
import com.snrm.simulation.SimulationStatus;
import com.snrm.simulation.UnresolvedEventException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Exporting <em>some</em> of a project's networks as a standalone project archive (FR-24).
 *
 * <p>Testcontainers rather than a unit test, and unavoidably so: every claim here is about what the
 * archive writer read out of a database and what the restore wrote back into one, through the
 * ordinary importer. {@code ProjectBundleJsonTest} beside it pins the half that needs neither.
 * Skipped when no Docker daemon is reachable, exactly like {@code RunDeletionTest} — the primary
 * local setup has no Docker.
 *
 * <p>The fixture is one project with three networks and one deliberate cross-network reference of
 * each kind, because a subset archive is only interesting where a project's rows span the cut:
 *
 * <ul>
 *   <li><strong>Alpha</strong>, the baseline, carrying a completed run with a metric row and a
 *       curve;</li>
 *   <li><strong>Bravo</strong>, which is <em>not</em> selected;</li>
 *   <li><strong>Charlie</strong>, forked from Bravo — so selecting Alpha and Charlie leaves a
 *       variant edge whose base did not travel;</li>
 *   <li>a scenario whose one event targets a node <em>in Bravo</em>;</li>
 *   <li>two products, one of which no network references, so "the whole catalogue travels" is
 *       falsifiable rather than incidentally true.</li>
 * </ul>
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: the archive and the restore each run
 * their own, and a rolled-back test transaction would check what Hibernate intended rather than what
 * the two services actually exchanged. {@link #cleanUp()} drops the projects, which cascades.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
@DisplayName("A subset of a project's networks, archived and restored — FR-24")
class ProjectSubsetArchiveTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /** The single research user of Phase 1; the services take it as an argument. */
    private static final long OWNER = 2424L;

    @Autowired
    private ProjectArchiveService archives;
    @Autowired
    private ProjectRestoreService restores;
    @Autowired
    private SimulationService simulations;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private NetworkRepository networks;
    @Autowired
    private NodeRepository nodes;
    @Autowired
    private LinkRepository links;
    @Autowired
    private ProductRepository products;
    @Autowired
    private NodeProductRepository nodeProducts;
    @Autowired
    private ConfigurationVariantRepository variants;
    @Autowired
    private DisruptionScenarioRepository scenarios;
    @Autowired
    private SimulationRunRepository runs;
    @Autowired
    private MetricResultRepository metrics;
    @Autowired
    private RunTimeseriesRepository timeseries;
    @Autowired
    private ObjectMapper json;

    private Project project;
    private Network alpha;
    private Network bravo;
    private Network charlie;
    private final List<Long> createdProjects = new ArrayList<>();

    // ------------------------------------------------------------------------- fixture

    @BeforeEach
    void buildExperiment() {
        project = newProject("FR-24 subset");

        // The catalogue is project-scoped. 'Spare part' is referenced by nothing, which is
        // what makes the first carry rule testable: it must still be in the restored project.
        Product gearbox = products.save(new Product(project, "Gearbox", 250.0));
        products.save(new Product(project, "Spare part", 12.5));

        alpha = buildNetwork("Alpha", true, gearbox);
        bravo = buildNetwork("Bravo", false, gearbox);
        charlie = buildNetwork("Charlie", false, gearbox);

        // Charlie was forked from Bravo, which the selection will leave behind.
        variants.save(new ConfigurationVariant(project, bravo, charlie, VariantOrigin.MANUAL,
                "{\"note\":\"+20% capacity at PLANT\"}"));

        // One scenario, one event, targeting a node of the network that will NOT travel.
        DisruptionScenario outage = new DisruptionScenario(project, "Cross-network outage");
        outage.setNumReplications(10);
        outage.setSeed(42L);
        DisruptionEvent event = new DisruptionEvent(outage, DisruptionTargetType.NODE,
                plantOf(bravo).getId(), null,
                DurationAmount.of(1, TimeUnit.DAY), DurationAmount.of(2, TimeUnit.DAY), 1.0);
        event.setRecoveryProfile(RecoveryProfileType.STEP);
        event.setProbability(1.0);
        outage.addEvent(event);
        scenarios.save(outage);

        completedRunOn(alpha);
    }

    @AfterEach
    void cleanUp() {
        // One delete each; fk_network_project and everything under it cascade (V2__domain.sql).
        for (Long id : createdProjects) {
            projects.deleteById(id);
        }
        createdProjects.clear();
    }

    // ----------------------------------------------------------- what a subset restores as

    @Test
    @DisplayName("a subset archive restores into a project holding exactly the named networks")
    void aSubsetRestoresIntoAProjectHoldingExactlyTheNamedNetworks() {
        Archive archive = archives.archive(project.getId(), OWNER,
                List.of(alpha.getId(), charlie.getId()));

        ArchiveReport report = restore(archive, "Alpha and Charlie");

        List<Network> restored = networks.findByProjectIdOrderByNameAscVersionAsc(report.projectId());
        assertThat(restored).extracting(Network::getName)
                .as("exactly the two networks named, and Bravo nowhere in sight (FR-24)")
                .containsExactly("Alpha", "Charlie");
        assertThat(report.restoredCounts().networks()).isEqualTo(2);

        // Carry rule 1: the WHOLE catalogue, including the entry no network references. A subset
        // that computed the used products would have dropped 'Spare part' here and, worse, could
        // drop one a node_product row names (FR-24).
        assertThat(products.findByProjectIdOrderByNameAsc(report.projectId()))
                .extracting(Product::getName)
                .containsExactlyInAnyOrder("Gearbox", "Spare part");

        // The selected networks' DONE runs travel with their metrics and their curves, as before.
        Network restoredAlpha = restored.stream()
                .filter(network -> network.getName().equals("Alpha")).findFirst().orElseThrow();
        List<SimulationRun> restoredRuns = runs.findByNetworkIdOrderByIdDesc(restoredAlpha.getId());
        assertThat(restoredRuns).hasSize(1);
        assertThat(restoredRuns.get(0).getImportedAt())
                .as("a restored run says so — decision 3, unchanged by the subset")
                .isNotNull();
        assertThat(metrics.findByRunId(restoredRuns.get(0).getId())).hasSize(1);
        assertThat(timeseries.findSeries(restoredRuns.get(0).getId())).hasSize(3);
    }

    @Test
    @DisplayName("every scenario travels, and the report says the archive was a subset")
    void everyScenarioTravelsAndTheSubsetIsReported() {
        Archive archive = archives.archive(project.getId(), OWNER,
                List.of(alpha.getId(), charlie.getId()));

        ArchiveReport report = restore(archive, "Scenario check");

        // Carry rule 2: the scenario is project-scoped and replayable across variants, so it travels
        // whether or not the network its event names did.
        assertThat(scenarios.findByProjectIdOrderByNameAsc(report.projectId()))
                .extracting(DisruptionScenario::getName)
                .containsExactly("Cross-network outage");

        assertThat(codesOf(report))
                .as("the subset is stated where the restored project is read, not left to be noticed")
                .contains(ArchiveReport.ARCHIVE_IS_SUBSET);

        Finding subset = findingOf(report, ArchiveReport.ARCHIVE_IS_SUBSET);
        assertThat(subset.subject()).isEqualTo("2 of 3 networks");
        assertThat(subset.message())
                .as("it is a copy, and the three carry rules are stated where the reader is")
                .contains("It is a copy")
                .contains("whole product catalogue")
                .contains("Every disruption scenario")
                .contains("EVENT_TARGET_UNRESOLVED")
                .contains("fork note");
    }

    @Test
    @DisplayName("an event targeting an excluded network restores dangling and refuses a submission")
    void anEventTargetingAnExcludedNetworkRestoresDanglingAndRefusesASubmission() {
        Archive archive = archives.archive(project.getId(), OWNER,
                List.of(alpha.getId(), charlie.getId()));

        ArchiveReport report = restore(archive, "Dangling target");

        DisruptionScenario restoredScenario = scenarios
                .findByProjectIdOrderByNameAsc(report.projectId()).get(0);
        DisruptionScenario withEvents =
                scenarios.findWithEventsById(restoredScenario.getId()).orElseThrow();

        assertThat(withEvents.getEvents())
                .as("the event is KEPT — dropping it would leave a scenario that runs cleanly with "
                        + "a disruption missing")
                .hasSize(1);
        assertThat(withEvents.getEvents().get(0).getTargetId())
                .as("a negative sentinel, which no AUTO_INCREMENT id can collide with — the same "
                        + "convention an already-dangling target restores through")
                .isNegative();

        assertThat(codesOf(report)).contains(ArchiveReport.EVENT_TARGET_UNRESOLVED);

        // And the point of the sentinel: the scenario is visibly unrunnable rather than quietly
        // runnable. The network has demand, so this refusal is the event's and not the network's.
        Network restoredAlpha = networks.findByProjectIdOrderByNameAscVersionAsc(report.projectId())
                .stream().filter(network -> network.getName().equals("Alpha"))
                .findFirst().orElseThrow();

        assertThatExceptionOfType(UnresolvedEventException.class)
                .isThrownBy(() -> simulations.submit(
                        new SimulationRequest(restoredAlpha.getId(), withEvents.getId(), null),
                        OWNER))
                .satisfies(refusal ->
                        assertThat(refusal.code()).isEqualTo("EVENT_TARGET_UNRESOLVED"));

        assertThat(runs.findByNetworkIdOrderByIdDesc(restoredAlpha.getId()))
                .as("the refusal happens before a run row is written — only the restored run is here")
                .hasSize(1);
    }

    @Test
    @DisplayName("a variant edge whose base was not selected is dropped, and the manifest counts it")
    void aDroppedForkNoteIsCounted() {
        Archive archive = archives.archive(project.getId(), OWNER,
                List.of(alpha.getId(), charlie.getId()));

        JsonNode selection = bundleOf(archive).path("manifest").path("selection");
        assertThat(selection.isObject()).as("a subset states what it selected").isTrue();
        assertThat(selection.get("selectedNetworks").intValue()).isEqualTo(2);
        assertThat(selection.get("projectNetworks").intValue()).isEqualTo(3);
        assertThat(selection.get("droppedVariantEdges").intValue())
                .as("Charlie was forked from Bravo, which did not travel — carry rule 3")
                .isEqualTo(1);
        assertThat(selection.get("excludedEventTargets").intValue())
                .as("one event pointed into Bravo — carry rule 2")
                .isEqualTo(1);
        assertThat(selection.get("networkKeys").size()).isEqualTo(2);

        // The restore skips the lineage row it cannot resolve, which is why the count is taken at
        // export: nothing on this side could re-derive it.
        ArchiveReport report = restore(archive, "Lineage check");
        assertThat(variants.findByProjectIdOrderByIdDesc(report.projectId()))
                .as("the fork note is genuinely gone; the finding is the only record of it")
                .isEmpty();
        assertThat(findingOf(report, ArchiveReport.ARCHIVE_IS_SUBSET).message())
                .contains("1 configuration-variant fork note was dropped");
    }

    @Test
    @DisplayName("the archive is a COPY — nothing in the source project changes")
    void archivingASubsetChangesNothingInTheSourceProject() {
        Map<String, Object> before = censusOfSource();

        Archive subset = archives.archive(project.getId(), OWNER,
                List.of(alpha.getId(), charlie.getId()));
        assertThat(subset.content()).isNotEmpty();
        restore(subset, "Copy check");

        assertThat(censusOfSource())
                .as("FR-24 copies and never moves: the selected networks, their runs and their "
                        + "results stay in the project that computed them")
                .isEqualTo(before);

        // Named explicitly, because "the counts match" would also hold if the wrong three rows had
        // been swapped for three others.
        assertThat(networks.findByProjectIdOrderByNameAscVersionAsc(project.getId()))
                .extracting(Network::getName).containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(runs.findByNetworkIdOrderByIdDesc(alpha.getId()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo(SimulationStatus.DONE);
                    assertThat(run.getImportedAt())
                            .as("the source run is still a locally computed one").isNull();
                });
    }

    // ---------------------------------------------------------------- what a subset refuses

    @Test
    @DisplayName("a network id from another project is refused, naming it, and nothing is written")
    void aNetworkFromAnotherProjectIsRefused() {
        Project other = newProject("FR-24 someone else's project");
        Product bolt = products.save(new Product(other, "Bolt", 3.0));
        Network elsewhere = buildNetworkIn(other, "Elsewhere", true, bolt);

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> archives.archive(project.getId(), OWNER,
                        List.of(alpha.getId(), elsewhere.getId())))
                .withMessageContaining(String.valueOf(elsewhere.getId()))
                .withMessageContaining("refused rather than skipped");

        // The whole point of refusing: the alternative is an archive quietly holding one network,
        // restored later into a project that is missing a configuration with nothing to say why.
        assertThat(networks.findByProjectIdOrderByNameAscVersionAsc(other.getId()))
                .as("and nothing in either project was touched").hasSize(1);
    }

    @Test
    @DisplayName("a selection that names nothing is refused rather than read as 'everything'")
    void anEmptySelectionIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> archives.archive(project.getId(), OWNER,
                        Collections.singletonList(null)))
                .withMessageContaining("Omit the parameter");
    }

    // ------------------------------------------------------- the whole-project path, unchanged

    @Test
    @DisplayName("the no-parameter archive is unchanged: no selection member, and the old filename")
    void theWholeProjectArchiveIsUnchanged() {
        Archive whole = archives.archive(project.getId(), OWNER);

        assertThat(whole.filename())
                .as("the whole-project name is produced by the statement it always was")
                .isEqualTo("FR-24 subset-archive.zip");

        JsonNode bundle = bundleOf(whole);
        assertThat(bundle.path("manifest").has("selection"))
                .as("null under non_null inclusion, so the member is not written at all — a "
                        + "whole-project bundle.json is the document this tool wrote before "
                        + "subsets existed")
                .isFalse();

        // Everything still travels: three networks, both products, the scenario, and the fork note
        // whose base is now present.
        assertThat(bundle.path("networks").size()).isEqualTo(3);
        assertThat(bundle.path("products").size()).isEqualTo(2);
        assertThat(bundle.path("variants").size()).isEqualTo(1);
        assertThat(bundle.path("manifest").path("counts").get("networks").intValue()).isEqualTo(3);

        // And the event resolves by name rather than being narrowed: nothing was excluded.
        JsonNode target = bundle.path("scenarios").get(0).path("events").get(0).path("target");
        assertThat(target.path("nodeName").asText()).isEqualTo("PLANT");
        assertThat(target.path("networkKey").asText()).isEqualTo("Bravo@v1");
    }

    @Test
    @DisplayName("null and an empty list are the whole project, and produce the same document")
    void omittedAndEmptyAreTheSameRequest() {
        JsonNode implicit = normalised(bundleOf(archives.archive(project.getId(), OWNER)));
        JsonNode explicitNull =
                normalised(bundleOf(archives.archive(project.getId(), OWNER, null)));
        JsonNode empty = normalised(bundleOf(archives.archive(project.getId(), OWNER, List.of())));

        // `exportedAt` is the one member that cannot agree across three calls; everything else must,
        // or the subset path has leaked into the path the reproducibility artefact takes.
        assertThat(explicitNull).isEqualTo(implicit);
        assertThat(empty).isEqualTo(implicit);
    }

    @Test
    @DisplayName("a subset naming every network is still a subset, and says so")
    void namingEveryNetworkIsStillASelection() {
        Archive all = archives.archive(project.getId(), OWNER,
                List.of(alpha.getId(), bravo.getId(), charlie.getId()));

        // A rule that depended on the selection's size would be one nobody could predict: "did I get
        // the plain archive?" would be answered by counting. The caller asked for a selection.
        assertThat(all.filename()).isEqualTo("FR-24 subset-3-networks-archive.zip");
        JsonNode selection = bundleOf(all).path("manifest").path("selection");
        assertThat(selection.get("selectedNetworks").intValue()).isEqualTo(3);
        assertThat(selection.get("droppedVariantEdges").intValue())
                .as("nothing was excluded, so nothing was dropped").isZero();
        assertThat(selection.get("excludedEventTargets").intValue()).isZero();
    }

    // -------------------------------------------------------------------------- fixtures

    private Project newProject(String name) {
        Project created = projects.save(new Project(name, OWNER));
        createdProjects.add(created.getId());
        return created;
    }

    private Network buildNetwork(String name, boolean baseline, Product product) {
        return buildNetworkIn(project, name, baseline, product);
    }

    /**
     * The smallest network the importer accepts without an error, with demand on its customer.
     *
     * <p>A PLANT and a CUSTOMER joined by one link satisfies {@code NetworkChecks}: supply present,
     * customer present, customer reachable, nothing orphaned. The demand row is what lets
     * {@code SimulationService.checkHasDemand} pass, so the submission in
     * {@link #anEventTargetingAnExcludedNetworkRestoresDanglingAndRefusesASubmission()} fails for
     * the reason that test is about and not for a missing fixture.
     */
    private Network buildNetworkIn(Project owner, String name, boolean baseline, Product product) {
        Network network = networks.save(new Network(owner, name, 1, baseline));

        Node newPlant = new Node(network, "PLANT", NodeType.PLANT);
        newPlant.setCapacity(Rate.of(500.0, TimeUnit.DAY));
        newPlant.setProcessingTime(DurationAmount.zero(TimeUnit.DAY));
        Node newCustomer = new Node(network, "CUSTOMER", NodeType.CUSTOMER);
        newCustomer.setCapacity(Rate.unconstrained(TimeUnit.DAY));
        newCustomer.setProcessingTime(DurationAmount.zero(TimeUnit.DAY));
        // The saved instances, not the arguments: a link and a node_product both need real ids.
        List<Node> saved = nodes.saveAll(List.of(newPlant, newCustomer));
        Node plant = saved.get(0);
        Node customer = saved.get(1);

        Link link = new Link(network, plant, customer);
        link.setLeadTime(DurationAmount.of(1, TimeUnit.DAY));
        link.setCapacity(Rate.of(500.0, TimeUnit.DAY));
        link.setUnitCost(1.0);
        links.save(link);

        NodeProduct demand = new NodeProduct(customer, product);
        demand.setDemand(Rate.of(20.0, TimeUnit.DAY));
        nodeProducts.save(demand);

        NodeProduct stock = new NodeProduct(plant, product);
        stock.setInitialInventory(1_000.0);
        nodeProducts.save(stock);

        return network;
    }

    private Node plantOf(Network network) {
        return nodes.findByNetworkId(network.getId()).stream()
                .filter(node -> node.getType() == NodeType.PLANT)
                .findFirst().orElseThrow();
    }

    /** A completed run with one metric row and a three-period curve, so results have to travel. */
    private void completedRunOn(Network network) {
        SimulationRun run = new SimulationRun(network, null,
                "{\"replications\":10,\"seed\":42,\"horizonPeriods\":3,\"engineVersion\":\""
                        + SimulationParams.ENGINE_VERSION + "\"}");
        run.setStatus(SimulationStatus.DONE);
        run.setStartedAt(Instant.parse("2026-08-01T09:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-08-01T09:05:00Z"));
        runs.save(run);

        metrics.save(new MetricResult(network, run, "FILL_RATE", MetricScope.NETWORK, null, 0.93));

        List<RunTimeseries> series = new ArrayList<>();
        for (int period = 1; period <= 3; period++) {
            RunTimeseries row = new RunTimeseries(run, period);
            row.setServedDemand(20.0);
            row.setTotalDemand(20.0);
            row.setCost(100.0);
            row.setBaselineServedDemand(20.0);
            row.setBaselineCost(100.0);
            series.add(row);
        }
        timeseries.saveAll(series);
    }

    // --------------------------------------------------------------------------- helpers

    private ArchiveReport restore(Archive archive, String name) {
        ArchiveReport report = restores.restore(archive.content(), name, OWNER);
        createdProjects.add(report.projectId());
        return report;
    }

    /** Every row of the source project that a "move" rather than a "copy" would have disturbed. */
    private Map<String, Object> censusOfSource() {
        Map<String, Object> census = new LinkedHashMap<>();
        census.put("networks", networks.findByProjectIdOrderByNameAscVersionAsc(project.getId()).size());
        census.put("products", products.findByProjectIdOrderByNameAsc(project.getId()).size());
        census.put("variants", variants.findByProjectIdOrderByIdDesc(project.getId()).size());
        census.put("scenarios", scenarios.findByProjectIdOrderByNameAsc(project.getId()).size());
        census.put("alphaRuns", runs.findByNetworkIdOrderByIdDesc(alpha.getId()).size());
        census.put("bravoNodes", nodes.findByNetworkId(bravo.getId()).size());
        census.put("charlieNodes", nodes.findByNetworkId(charlie.getId()).size());
        return census;
    }

    /** {@code bundle.json} out of the zip the service returned. */
    private JsonNode bundleOf(Archive archive) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.content()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (ArchiveSchema.BUNDLE_ENTRY.equals(entry.getName())) {
                    return json.readTree(zip.readAllBytes());
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        throw new AssertionError("The archive holds no " + ArchiveSchema.BUNDLE_ENTRY);
    }

    /** The same document with the one member two calls cannot agree on removed. */
    private static JsonNode normalised(JsonNode bundle) {
        JsonNode manifest = bundle.path("manifest");
        if (manifest instanceof ObjectNode object) {
            object.remove("exportedAt");
        }
        return bundle;
    }

    private static List<String> codesOf(ArchiveReport report) {
        return report.findings().stream().map(Finding::code).toList();
    }

    private static Finding findingOf(ArchiveReport report, String code) {
        return report.findings().stream()
                .filter(finding -> finding.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + code + " finding in " +
                        codesOf(report)));
    }
}
