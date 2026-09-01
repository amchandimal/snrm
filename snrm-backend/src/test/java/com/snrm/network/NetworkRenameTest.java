package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import com.snrm.simulation.SimulationRun;
import com.snrm.simulation.SimulationRunRepository;
import com.snrm.simulation.SimulationStatus;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A rename is not a structural edit (FR-29).
 *
 * <p>The feature is a deleted line — {@code guard.assertMutable} at the top of
 * {@link NetworkService#update} — so the test that matters is not that the rename works but that
 * <strong>nothing else moved with it</strong>. Three claims:
 *
 * <ol>
 *   <li><strong>A frozen network renames, and its baseline flag moves.</strong> Neither is an input
 *       to any metric, so freezing them protects nothing immutability exists to protect — while a batch
 *       import names networks after their files (FR-28), which makes the network most in need of a
 *       better name routinely one that has already been run.</li>
 *   <li><strong>A frozen network's time base still does not move</strong>, and neither does its
 *       structure or its existence. The time base is the guard's most consequential use: a result is
 *       stated in periods, so redefining a period turns "TTR = 14" from fourteen days into fourteen
 *       hours without changing a stored number. If one assertion in this class is load-bearing, it
 *       is that one — it is what makes the exemption a carve-out rather than a hole.</li>
 *   <li><strong>{@code assertNoOtherBaseline} behaves the same, and one thing it could not do
 *       before now works.</strong> The check refuses a second baseline and never moves the flag, so
 *       designating a new one is two requests. While {@code update} was guarded, a frozen incumbent
 *       could not be cleared, which made {@code BASELINE_ALREADY_SET} unresolvable in exactly the
 *       case FR-29 names.</li>
 * </ol>
 *
 * <p>Testcontainers, because the freeze is derived from {@code simulation_run} rows and every claim
 * here is about a real query against them. Skipped when no Docker daemon is reachable, exactly like
 * {@code RunDeletionTest}. Deliberately not {@code @Transactional}: each service call
 * commits, and {@link #cleanUp()} drops the project, which cascades.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
@DisplayName("Renaming a frozen network — FR-29")
class NetworkRenameTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /** The single research user of Phase 1; the services take it as an argument. */
    private static final long OWNER = 1729L;

    @Autowired
    private NetworkService networkService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private NetworkRepository networks;
    @Autowired
    private NetworkMutationGuard guard;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private SimulationRunRepository runs;

    private Project project;
    private Network frozen;
    private final List<Long> createdProjects = new ArrayList<>();

    @BeforeEach
    void createFrozenNetwork() {
        project = newProject("FR-29 rename");
        // The name a batch import would have given it: the file's, extension stripped (FR-28).
        frozen = networks.save(new Network(project, "Baseline_v3_FINAL", 1, false));
        freeze(frozen);
        assertThat(guard.isMutable(frozen.getId()))
                .as("the fixture is only interesting while the network is frozen").isFalse();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdProjects) {
            projects.deleteById(id);
        }
        createdProjects.clear();
    }

    // ------------------------------------------------------------- what the freeze stops covering

    @Test
    @DisplayName("a network with a completed run against it renames")
    void aFrozenNetworkRenames() {
        NetworkDto renamed = networkService.update(frozen.getId(), OWNER,
                new NetworkRequest("Dual sourcing, EU", false));

        assertThat(renamed.name()).isEqualTo("Dual sourcing, EU");
        assertThat(renamed.editable())
                .as("still frozen — the rename did not release anything, it was never covered")
                .isFalse();
        assertThat(networks.findById(frozen.getId()).orElseThrow().getName())
                .isEqualTo("Dual sourcing, EU");
        assertThat(runs.findByNetworkIdOrderByIdDesc(frozen.getId()))
                .as("and the run that froze it is untouched — its input network is the same rows")
                .hasSize(1);
    }

    @Test
    @DisplayName("the baseline flag moves onto a frozen network — the judgement comes after the runs")
    void theBaselineFlagMovesOntoAFrozenNetwork() {
        NetworkDto flagged = networkService.update(frozen.getId(), OWNER,
                new NetworkRequest("Baseline_v3_FINAL", true));

        assertThat(flagged.baseline()).isTrue();
        assertThat(networks.findByProjectIdAndBaselineIsTrue(project.getId()))
                .get()
                .extracting(Network::getId)
                .isEqualTo(frozen.getId());
    }

    @Test
    @DisplayName("name and baseline flag travel together: a rename that omits the flag clears it")
    void nameAndFlagTravelTogether() {
        networkService.update(frozen.getId(), OWNER, new NetworkRequest("Named", true));

        // Not a defect, and it is why the dashboard edits the two as one control: PUT
        // replaces the network's fields, and a client that sends only a name has said "not baseline".
        NetworkDto afterRename = networkService.update(frozen.getId(), OWNER,
                new NetworkRequest("Renamed again", false));

        assertThat(afterRename.baseline()).isFalse();
        assertThat(networks.findByProjectIdAndBaselineIsTrue(project.getId())).isEmpty();
    }

    // --------------------------------------------------------------- what the freeze still covers

    @Test
    @DisplayName("the TIME BASE of a frozen network is still refused — the guard's sharpest use")
    void aFrozenNetworkKeepsItsTimeBase() {
        TimeBaseRequest toHours = new TimeBaseRequest(new DurationDto(1.0, TimeUnit.HOUR), 240,
                RoundingPolicy.NEAREST);

        assertThatExceptionOfType(NetworkImmutableException.class)
                .isThrownBy(() -> networkService.setTimeBase(frozen.getId(), OWNER, toHours))
                .satisfies(refusal -> assertThat(refusal.code()).isEqualTo("NETWORK_IMMUTABLE"));

        // The reason, restated as an assertion: results are stated in periods, so this network's
        // "TTR = 14" would have become fourteen hours instead of fourteen days with no stored number
        // changing.
        Network reread = networks.findById(frozen.getId()).orElseThrow();
        assertThat(reread.getPeriodLength().getUnit()).isEqualTo(TimeUnit.DAY);
        assertThat(reread.getHorizonPeriods()).isEqualTo(52);
    }

    @Test
    @DisplayName("a frozen network still refuses a new node, and still refuses to be deleted")
    void aFrozenNetworkRefusesEverythingStructural() {
        // NodeService is untouched by FR-29: a node is structure, and structure is what the freeze
        // is about.
        assertThatExceptionOfType(NetworkImmutableException.class)
                .isThrownBy(() -> nodeService.create(frozen.getId(), OWNER,
                        new NodeRequest("PLANT-2", NodeType.PLANT, null, null, 0, 0, 0, null, null,
                                null, null, null, null, null)));

        // And deleting a network a run points at would destroy that result's inputs outright.
        assertThatExceptionOfType(NetworkImmutableException.class)
                .isThrownBy(() -> networkService.delete(frozen.getId(), OWNER));

        assertThat(networks.findById(frozen.getId())).isPresent();
    }

    // ------------------------------------------------------ assertNoOtherBaseline, now reachable

    @Test
    @DisplayName("a second baseline is still refused, frozen or not — the check did not change")
    void aSecondBaselineIsStillRefused() {
        Network incumbent = networks.save(new Network(project, "The baseline", 1, true));

        assertThatExceptionOfType(MultipleBaselineException.class)
                .isThrownBy(() -> networkService.update(frozen.getId(), OWNER,
                        new NetworkRequest("Baseline_v3_FINAL", true)))
                .satisfies(refusal -> {
                    assertThat(refusal.code()).isEqualTo("BASELINE_ALREADY_SET");
                    assertThat(refusal.getCurrentBaselineNetworkId()).isEqualTo(incumbent.getId());
                });

        assertThat(networks.findById(frozen.getId()).orElseThrow().isBaseline()).isFalse();
    }

    @Test
    @DisplayName("the flag can be moved OFF a frozen incumbent — which the guard used to make impossible")
    void theFlagCanBeMovedOffAFrozenIncumbent() {
        // The incumbent is frozen and holds the flag. Before FR-29 this was a dead end: clearing the
        // flag was a write to a frozen network, so BASELINE_ALREADY_SET could never be resolved and
        // the project's baseline was whichever network happened to be run first.
        networkService.update(frozen.getId(), OWNER, new NetworkRequest("Baseline_v3_FINAL", true));
        Network successor = networks.save(new Network(project, "Dual sourcing", 1, false));

        // The check never moves the flag; it refuses, and moving it is two requests.
        assertThatExceptionOfType(MultipleBaselineException.class)
                .isThrownBy(() -> networkService.update(successor.getId(), OWNER,
                        new NetworkRequest("Dual sourcing", true)));

        // Step one: clear the frozen incumbent. This is the line FR-29 makes possible — before it,
        // it threw NETWORK_IMMUTABLE and there was no step two.
        networkService.update(frozen.getId(), OWNER, new NetworkRequest("Baseline_v3_FINAL", false));
        NetworkDto promoted = networkService.update(successor.getId(), OWNER,
                new NetworkRequest("Dual sourcing", true));

        assertThat(promoted.baseline()).isTrue();
        assertThat(networks.findByProjectIdAndBaselineIsTrue(project.getId()))
                .get()
                .extracting(Network::getId)
                .isEqualTo(successor.getId());
    }

    // -------------------------------------------------------------------------- fixtures

    private Project newProject(String name) {
        Project created = projects.save(new Project(name, OWNER));
        createdProjects.add(created.getId());
        return created;
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
