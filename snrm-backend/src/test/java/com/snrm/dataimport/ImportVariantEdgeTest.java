package com.snrm.dataimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snrm.network.ConfigurationVariantDto;
import com.snrm.network.ConfigurationVariantRepository;
import com.snrm.network.Network;
import com.snrm.network.NetworkRepository;
import com.snrm.network.NetworkService;
import com.snrm.network.VariantOrigin;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import com.snrm.simulation.SimulationRun;
import com.snrm.simulation.SimulationRunRepository;
import com.snrm.simulation.SimulationStatus;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * An import that is also a fork: {@code baseNetworkId} on {@code POST /networks/import}
 * (FR-28).
 *
 * <p>Testcontainers rather than a unit test, and unavoidably so — every claim here is about two rows
 * that must appear together in a database, or not at all. Skipped when no Docker daemon is reachable,
 * exactly like {@code RunDeletionTest} and {@code ProjectSubsetArchiveTest}: the primary local setup
 * has no Docker.
 *
 * <p>Four claims, and each one is a way the feature could be half-implemented and still look right:
 *
 * <ol>
 *   <li><strong>The edge exists, and it is the edge the provenance tree draws.</strong> Not a field
 *       on the report, not a row only this test knows how to find — the assertion goes through
 *       {@code GET /networks/{id}/variants}, which is what the fork forest is rendered from.</li>
 *   <li><strong>It is written with the network, not after it.</strong> An external caller cannot
 *       observe a transaction from inside, so what is asserted is the observable form of that: the
 *       edge is already there in the same call that returned the network, and a refused import
 *       leaves neither row. The rest is {@code ImportService.recordVariantEdge} being one statement
 *       inside one {@code @Transactional} method, which is where the guarantee actually lives.</li>
 *   <li><strong>A dry run creates neither.</strong> {@code validateOnly=true} is the wizard's report
 *       step, and an edge pointing at a network that was never created is a foreign key with nothing
 *       behind it.</li>
 *   <li><strong>A base from another project is refused, and nothing is created.</strong> The
 *       alternative — import it and drop the edge — produces a variant that looks exactly like a
 *       file the wizard was told was independent.</li>
 * </ol>
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: a rolled-back test transaction would
 * check what Hibernate intended rather than what InnoDB holds, and claims 2 and 4 are about the
 * second. {@link #cleanUp()} drops the projects, which cascades.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
@DisplayName("An imported network recorded as a variant of its base — FR-28")
class ImportVariantEdgeTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /** The single research user of Phase 1; the services take it as an argument. */
    private static final long OWNER = 8128L;

    @Autowired
    private ImportService imports;
    @Autowired
    private NetworkService networkService;
    @Autowired
    private NetworkRepository networks;
    @Autowired
    private ConfigurationVariantRepository variants;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private SimulationRunRepository runs;
    @Autowired
    private ObjectMapper json;

    private Project project;
    private Network base;
    private final List<Long> createdProjects = new ArrayList<>();

    @BeforeEach
    void createBase() {
        project = newProject("FR-28 batch");
        // The base of a batch's variant edges is an ordinary network; it does not have to be the
        // project's baseline, and the wizard's roles step keeps the two names apart.
        base = networks.save(new Network(project, "Baseline_v3_FINAL", 1, true));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdProjects) {
            projects.deleteById(id);
        }
        createdProjects.clear();
    }

    // ------------------------------------------------------------------ the edge is recorded

    @Test
    @DisplayName("an import naming a base is a CONFIGURATION_VARIANT of it, visible in the fork forest")
    void anImportNamingABaseIsRecordedAsAVariant() {
        ImportReport report = importNetwork("Variant A", base.getId(), null, false, GOOD_LINKS);

        assertThat(report.committed()).isTrue();

        List<ConfigurationVariantDto> forest = networkService.variantsOf(base.getId(), OWNER);
        assertThat(forest)
                .as("GET /networks/{id}/variants is what draws the batch as a fork forest")
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.network().id()).isEqualTo(report.networkId());
                    assertThat(edge.network().name()).isEqualTo("Variant A");
                    assertThat(edge.baseNetworkId()).isEqualTo(base.getId());
                    assertThat(edge.generatedBy())
                            .as("a person chose these files and said what each one was; SEARCH "
                                    + "belongs to the Phase 2 engine")
                            .isEqualTo(VariantOrigin.MANUAL);
                    assertThat(edge.leverChanges())
                            .as("nothing diffs two networks, so an edge with no note carries null "
                                    + "rather than an invented diff")
                            .isNull();
                });
    }

    @Test
    @DisplayName("the edge is already there in the call that created the network")
    void theEdgeIsWrittenWithTheNetwork() {
        ImportReport report = importNetwork("Variant B", base.getId(), null, false, GOOD_LINKS);

        // The observable form of "in the same transaction": there is no second request between the
        // network appearing and the edge appearing, so no reader can see the network unlinked.
        assertThat(variants.findByNetworkId(report.networkId()))
                .as("one call, both rows — never a network briefly without its edge (FR-28)")
                .isPresent();
    }

    @Test
    @DisplayName("the note is whatever the request carried — and nothing computes one")
    void theNoteIsWhateverTheRequestCarried() {
        ImportReport report = importNetwork("Variant C", base.getId(),
                node("{\"note\":\"second supplier qualified\"}"), false, GOOD_LINKS);

        assertThat(networkService.variantsOf(base.getId(), OWNER))
                .singleElement()
                .satisfies(edge -> assertThat(edge.leverChanges().path("note").asText())
                        .isEqualTo("second supplier qualified"));
        assertThat(report.networkId()).isNotNull();
    }

    @Test
    @DisplayName("no baseNetworkId is an independent network, exactly as every import was before")
    void anImportWithNoBaseIsIndependent() {
        ImportReport report = importNetwork("Independent", null, null, false, GOOD_LINKS);

        assertThat(report.committed()).isTrue();
        assertThat(variants.findByNetworkId(report.networkId()))
                .as("an independent file is a root of the forest, not a variant of anything")
                .isEmpty();
        assertThat(networkService.variantsOf(base.getId(), OWNER)).isEmpty();
    }

    @Test
    @DisplayName("a frozen base takes variants like any other — the freeze governs edits to it")
    void aFrozenBaseAcceptsAVariant() {
        freeze(base);

        ImportReport report = importNetwork("Variant of a frozen base", base.getId(), null, false,
                GOOD_LINKS);

        // Reading a configuration to fork from it is not editing it — the same rule clone follows,
        // and the usual reason to fork is that the base has results worth building on.
        assertThat(report.committed()).isTrue();
        assertThat(variants.findByNetworkId(report.networkId())).isPresent();
    }

    // ------------------------------------------------------------------------- the dry run

    @Test
    @DisplayName("validateOnly creates neither the network nor the edge")
    void aDryRunCreatesNeither() {
        ImportReport report = importNetwork("Never created", base.getId(), null, true, GOOD_LINKS);

        assertThat(report.valid()).as("the file itself is fine — this is the wizard's report step")
                .isTrue();
        assertThat(report.committed()).isFalse();
        assertThat(report.networkId()).isNull();

        assertThat(networkService.variantsOf(base.getId(), OWNER))
                .as("an edge whose network was never created is a foreign key with nothing behind "
                        + "it, not a lesser version of the feature (FR-28)")
                .isEmpty();
        assertThat(networks.findByProjectIdOrderByNameAscVersionAsc(project.getId()))
                .extracting(Network::getName)
                .containsExactly("Baseline_v3_FINAL");
    }

    @Test
    @DisplayName("a refused file leaves no network and no edge")
    void aRefusedImportLeavesNeither() {
        ImportReport report = importNetwork("Bad row", base.getId(), null, false, GHOST_LINK);

        assertThat(report.valid()).isFalse();
        assertThat(report.committed()).isFalse();
        assertThat(report.diagnostics())
                .extracting(ImportDiagnostic::code)
                .contains(ImportCheck.UNKNOWN_NODE_REFERENCE.name());

        assertThat(networkService.variantsOf(base.getId(), OWNER))
                .as("the write is the last statement of the method, so a refusal never reaches it")
                .isEmpty();
        assertThat(networks.findByProjectIdOrderByNameAscVersionAsc(project.getId())).hasSize(1);
    }

    // -------------------------------------------------------------------------- refusals

    @Test
    @DisplayName("a base in another project is refused by name, and nothing is created")
    void aCrossProjectBaseIsRefused() {
        Project other = newProject("Someone else's experiment");
        Network foreignBase = networks.save(new Network(other, "Their baseline", 1, true));

        assertThatExceptionOfType(BaseNetworkScopeException.class)
                .isThrownBy(() -> importNetwork("Orphan", foreignBase.getId(), null, false,
                        GOOD_LINKS))
                .satisfies(refusal -> {
                    assertThat(refusal.code()).isEqualTo("BASE_NETWORK_OUT_OF_SCOPE");
                    assertThat(refusal.getBaseNetworkId()).isEqualTo(foreignBase.getId());
                })
                .withMessageContaining(String.valueOf(other.getId()))
                .withMessageContaining(String.valueOf(project.getId()));

        // Never "created, silently unlinked": a variant that arrived as a root is indistinguishable
        // from a file the wizard was told was independent (FR-28).
        assertThat(networks.findByProjectIdOrderByNameAscVersionAsc(project.getId()))
                .extracting(Network::getName)
                .containsExactly("Baseline_v3_FINAL");
        assertThat(networkService.variantsOf(foreignBase.getId(), OWNER)).isEmpty();
    }

    @Test
    @DisplayName("a cross-project base is refused on a dry run too, before the file is even staged")
    void aCrossProjectBaseIsRefusedOnADryRun() {
        Project other = newProject("Another experiment");
        Network foreignBase = networks.save(new Network(other, "Their baseline", 1, true));

        // The point of resolving the base before staging: the wizard sends one base id with every
        // file of a batch, so a wrong one is caught before the first of eight commits.
        assertThatExceptionOfType(BaseNetworkScopeException.class)
                .isThrownBy(() -> importNetwork("Orphan", foreignBase.getId(), null, true,
                        GOOD_LINKS));
    }

    @Test
    @DisplayName("a base that does not exist is the 404 every other id gives")
    void anUnknownBaseIsNotFound() {
        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> importNetwork("Orphan", 999_999L, null, false, GOOD_LINKS));

        assertThat(networks.findByProjectIdOrderByNameAscVersionAsc(project.getId())).hasSize(1);
    }

    @Test
    @DisplayName("another user's base is the same 404 — missing and not-yours are one answer")
    void anotherUsersBaseIsNotFound() {
        // Our project, their network: the project check passes and the base check is what refuses,
        // which is the case this test would otherwise not reach.
        Project theirs = projects.save(new Project("Another researcher", OWNER + 1));
        createdProjects.add(theirs.getId());
        Network theirBase = networks.save(new Network(theirs, "Their baseline", 1, true));

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> importNetwork("Orphan", theirBase.getId(), null, false,
                        GOOD_LINKS))
                .withMessageContaining(String.valueOf(theirBase.getId()));

        assertThat(networks.findByProjectIdOrderByNameAscVersionAsc(project.getId())).hasSize(1);
    }

    // -------------------------------------------------------------------------- fixtures

    private static final String NODES = """
            name,type,capacity_value,processing_time_value
            PLANT-1,PLANT,500,0
            CUST-1,CUSTOMER,,0
            """;

    /** One arc, so the customer is reachable and stage 2 has nothing to complain about. */
    private static final String GOOD_LINKS = """
            source,target,lead_time_value,capacity_value,unit_cost
            PLANT-1,CUST-1,1,500,2
            """;

    /** A link naming a node the file does not declare — one row-level error, and no network. */
    private static final String GHOST_LINK = """
            source,target,lead_time_value,capacity_value,unit_cost
            PLANT-1,GHOST,1,500,2
            """;

    private static final String META = """
            period_length_value,period_length_unit,horizon_periods,rounding_policy
            1,DAY,52,NEAREST
            """;

    private Project newProject(String name) {
        Project created = projects.save(new Project(name, OWNER));
        createdProjects.add(created.getId());
        return created;
    }

    private ImportReport importNetwork(String name, Long baseNetworkId, JsonNode leverChanges,
            boolean validateOnly, String links) {
        return imports.importNetworkFiles(files(links),
                request(name, baseNetworkId, leverChanges, validateOnly), OWNER);
    }

    private ImportRequest request(String name, Long baseNetworkId, JsonNode leverChanges,
            boolean validateOnly) {
        return new ImportRequest(project.getId(), name, false, validateOnly, null, null, null, null,
                baseNetworkId, leverChanges);
    }

    /**
     * The CSV set as bytes, through the entry point the archive already uses.
     *
     * <p>{@code importNetworkFiles} rather than the multipart form: {@link UploadedFile} exists so
     * the importer needs no web layer, and the two entry points share everything after the parse.
     */
    private static List<UploadedFile> files(String links) {
        return List.of(csv("nodes.csv", NODES), csv("links.csv", links),
                csv("network_meta.csv", META));
    }

    private static UploadedFile csv(String filename, String content) {
        return new UploadedFile(filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode node(String raw) {
        try {
            return json.readTree(raw);
        } catch (IOException unreachable) {
            throw new UncheckedIOException(unreachable);
        }
    }

    /** A DONE run, which is what freezes a network — the freeze is these rows (FR-20). */
    private void freeze(Network network) {
        SimulationRun run = new SimulationRun(network, null, "{}");
        run.setStatus(SimulationStatus.DONE);
        run.setStartedAt(Instant.now());
        run.setFinishedAt(Instant.now());
        runs.save(run);
    }
}
