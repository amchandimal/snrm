package com.snrm.simulation;

import com.snrm.metrics.MetricResult;
import com.snrm.metrics.MetricResultRepository;
import com.snrm.metrics.MetricScope;
import com.snrm.network.Network;
import com.snrm.network.NetworkMutationGuard;
import com.snrm.network.NetworkRepository;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Discarding a run, and what that does to the freeze (FR-20).
 *
 * <p>Three claims, and every one of them is about the <em>database</em> rather than about a policy
 * object, which is why this is a Testcontainers test rather than a unit test like
 * {@link SimulationStatusTest} beside it:
 *
 * <ol>
 *   <li><strong>Deleting the last locking run releases the freeze</strong>, with nothing else
 *       touched — the immutability is derived from the run rows by
 *       {@link NetworkMutationGuard}, and this is the assertion that keeps it derived. The moment
 *       someone adds an {@code unlocked} column, this test stays green while being wrong, so it
 *       also asserts the intermediate state: two runs, delete one, still frozen.</li>
 *   <li><strong>Deletion is refused while a job owns the run</strong>, and refused <em>whole</em> for
 *       the network-wide form. Nothing is half-deleted.</li>
 *   <li><strong>The cascade takes everything the run produced and nothing else</strong> — all four
 *       {@code ON DELETE CASCADE} foreign keys of {@code V2}/{@code V9} fire, while the network's
 *       topological rows ({@code run_id IS NULL}) survive. Nothing in application code
 *       deletes those children, so this test is the only thing standing between a future migration
 *       and orphaned result rows.</li>
 * </ol>
 *
 * <p>Skipped when no Docker daemon is reachable, exactly like {@code SnrmApplicationTests} — the
 * primary local setup has no Docker, and a repository claim cannot honestly be checked against
 * anything but a real MySQL.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. A rolled-back test transaction would
 * check what Hibernate intended rather than what InnoDB did, and the whole point here is the second.
 * Each service call commits; {@link #cleanUp()} drops the project, which cascades.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
@DisplayName("Run deletion and the freeze it releases — FR-20, ")
class RunDeletionTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /** The single research user of Phase 1; the services take it as an argument. */
    private static final long OWNER = 4242L;

    @Autowired
    private SimulationService simulations;
    @Autowired
    private SimulationRunRepository runs;
    @Autowired
    private RunTimeseriesRepository timeseries;
    @Autowired
    private RunNodeTimeseriesRepository nodeTimeseries;
    @Autowired
    private RunLinkTimeseriesRepository linkTimeseries;
    @Autowired
    private MetricResultRepository metricResults;
    @Autowired
    private NetworkRepository networks;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private NetworkMutationGuard guard;

    private Project project;
    private Network network;

    @BeforeEach
    void createNetwork() {
        project = projects.save(new Project("FR-20 discard", OWNER));
        network = networks.save(new Network(project, "Baseline", 1, true));
    }

    @AfterEach
    void cleanUp() {
        // One delete; fk_network_project and everything under it cascade (V2__domain.sql).
        projects.deleteById(project.getId());
    }

    // ------------------------------------------------------------------ the freeze, released

    @Test
    @DisplayName("deleting the last locking run makes the network editable again — nothing else to reset")
    void deletingTheLastLockingRunReleasesTheFreeze() {
        long firstRun = completedRun();
        long secondRun = completedRun();

        assertThat(guard.isMutable(network.getId()))
                .as("two DONE runs freeze it").isFalse();

        simulations.delete(firstRun, OWNER);
        assertThat(guard.isMutable(network.getId()))
                .as("one locking run is still there, so the freeze stands — this is the assertion "
                        + "that would catch a client, or a stored flag, deciding for itself")
                .isFalse();

        simulations.delete(secondRun, OWNER);
        assertThat(guard.isMutable(network.getId()))
                .as("the last locking run is gone, so the network is editable again with no "
                        + "further mechanism (FR-20)")
                .isTrue();
    }

    @Test
    @DisplayName("a FAILED run never froze anything, and deleting it changes nothing")
    void deletingANonLockingRunIsUneventful() {
        long failed = run(SimulationStatus.FAILED);

        assertThat(guard.isMutable(network.getId())).isTrue();
        simulations.delete(failed, OWNER);
        assertThat(guard.isMutable(network.getId())).isTrue();
        assertThat(runs.findById(failed)).isEmpty();
    }

    @Test
    @DisplayName("discarding every run of a network releases the freeze in one request")
    void discardingAllRunsReleasesTheFreeze() {
        completedRun();
        completedRun();
        run(SimulationStatus.CANCELLED);

        assertThat(guard.isMutable(network.getId())).isFalse();

        assertThat(simulations.deleteNetworkRuns(network.getId(), OWNER))
                .as("every run of the network, active or not — the cancelled one included")
                .isEqualTo(3);

        assertThat(runs.findByNetworkIdOrderByIdDesc(network.getId())).isEmpty();
        assertThat(guard.isMutable(network.getId())).isTrue();
    }

    @Test
    @DisplayName("a network with no runs is a 204, not a 404 — the caller asked for exactly this")
    void discardingNothingSucceeds() {
        assertThat(simulations.deleteNetworkRuns(network.getId(), OWNER)).isZero();
    }

    // ---------------------------------------------------------------- refused while active

    @Test
    @DisplayName("deleting a RUNNING run is refused with RUN_ACTIVE, and the row survives")
    void deletingARunningRunIsRefused() {
        long running = run(SimulationStatus.RUNNING);

        assertThatExceptionOfType(RunActiveException.class)
                .isThrownBy(() -> simulations.delete(running, OWNER))
                .satisfies(refusal -> assertThat(refusal.code()).isEqualTo("RUN_ACTIVE"))
                .withMessageContaining("DELETE /jobs/");

        assertThat(runs.findById(running)).as("nothing was deleted").isPresent();
        assertThat(guard.isMutable(network.getId()))
                .as("and the network is still frozen by it").isFalse();
    }

    @Test
    @DisplayName("a QUEUED run refuses too — a worker has not started, but it is about to")
    void deletingAQueuedRunIsRefused() {
        long queued = run(SimulationStatus.QUEUED);

        assertThatExceptionOfType(RunActiveException.class)
                .isThrownBy(() -> simulations.delete(queued, OWNER));

        assertThat(runs.findById(queued)).isPresent();
    }

    @Test
    @DisplayName("one active run refuses the WHOLE network discard — no silent skipping")
    void oneActiveRunRefusesTheWholeDiscard() {
        long completed = completedRun();
        long running = run(SimulationStatus.RUNNING);

        assertThatExceptionOfType(RunActiveException.class)
                .isThrownBy(() -> simulations.deleteNetworkRuns(network.getId(), OWNER))
                .withMessageContaining("Nothing was deleted");

        // The point of refusing whole: a half-discard would have destroyed the completed result and
        // left the network frozen anyway, since the RUNNING run locks it too.
        assertThat(runs.findById(completed))
                .as("the completed run was NOT deleted on the way past").isPresent();
        assertThat(runs.findById(running)).isPresent();
        assertThat(guard.isMutable(network.getId())).isFalse();
    }

    // ------------------------------------------------------------------------- the cascade

    @Test
    @DisplayName("the cascade removes every row the run produced, and only those")
    void deletingARunLeavesNoOrphans() {
        long runId = completedRunWithResults();
        long survivingRunId = completedRunWithResults();

        assertThat(metricResults.findByRunId(runId)).hasSize(2);
        assertThat(timeseries.findSeries(runId)).hasSize(3);
        assertThat(nodeTimeseries.findSeries(runId)).hasSize(3);
        assertThat(linkTimeseries.findSeries(runId)).hasSize(3);

        // A topological row: it belongs to the network, not to any run, and carries run_id = NULL
        // Deleting a run must not touch it.
        metricResults.save(new MetricResult(network, null, "DENSITY", MetricScope.NETWORK, null,
                0.42));

        simulations.delete(runId, OWNER);

        assertThat(runs.findById(runId)).isEmpty();
        assertThat(metricResults.findByRunId(runId))
                .as("fk_mr_run is ON DELETE CASCADE (V2__domain.sql) — no orphaned metric rows")
                .isEmpty();
        assertThat(timeseries.findSeries(runId))
                .as("fk_timeseries_run (V2__domain.sql)").isEmpty();
        assertThat(nodeTimeseries.findSeries(runId))
                .as("fk_node_ts_run (V9__element_timeseries.sql)").isEmpty();
        assertThat(linkTimeseries.findSeries(runId))
                .as("fk_link_ts_run (V9__element_timeseries.sql)").isEmpty();

        assertThat(metricResults.findByNetworkIdAndRunIsNull(network.getId()))
                .as("the network's structural suite survives: nothing about the structure changed")
                .hasSize(1);
        assertThat(metricResults.findByRunId(survivingRunId))
                .as("and the other run's results are untouched").hasSize(2);
        assertThat(nodeTimeseries.findSeries(survivingRunId)).hasSize(3);
    }

    @Test
    @DisplayName("the whole-network discard cascades for every run it takes")
    void discardingAllRunsLeavesNoOrphans() {
        long first = completedRunWithResults();
        long second = completedRunWithResults();

        simulations.deleteNetworkRuns(network.getId(), OWNER);

        for (long runId : List.of(first, second)) {
            assertThat(metricResults.findByRunId(runId)).isEmpty();
            assertThat(timeseries.findSeries(runId)).isEmpty();
            assertThat(nodeTimeseries.findSeries(runId)).isEmpty();
            assertThat(linkTimeseries.findSeries(runId)).isEmpty();
        }
    }

    // -------------------------------------------------------------------------- ownership

    @Test
    @DisplayName("another user's run is a 404, the same answer every other run read gives")
    void anotherUsersRunIsNotFound() {
        long runId = completedRun();

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> simulations.delete(runId, OWNER + 1));

        assertThat(runs.findById(runId)).isPresent();
    }

    @Test
    @DisplayName("another user's network is a 404 for the whole-network discard too")
    void anotherUsersNetworkIsNotFound() {
        completedRun();

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> simulations.deleteNetworkRuns(network.getId(), OWNER + 1));

        assertThat(runs.findByNetworkIdOrderByIdDesc(network.getId())).hasSize(1);
    }

    // ---------------------------------------------------------------- restored runs

    @Test
    @DisplayName("a restored archive run is deletable — the warning belongs in the confirm, not here")
    void aRestoredRunIsDeletable() {
        SimulationRun restored = newRun(SimulationStatus.DONE);
        restored.setImportedAt(Instant.now());
        restored.setSourceRunId(42L);
        long runId = runs.save(restored).getId();

        assertThat(simulations.networkRuns(network.getId(), OWNER))
                .singleElement()
                .satisfies(dto -> {
                    assertThat(dto.importedAt())
                            .as("the field the discard dialog raises its extra warning from")
                            .isNotNull();
                    assertThat(dto.sourceRunId()).isEqualTo(42L);
                });

        // Refusing would make a restored project permanently uneditable, which is the opposite of
        // what the archive is for.
        simulations.delete(runId, OWNER);
        assertThat(runs.findById(runId)).isEmpty();
    }

    @Test
    @DisplayName("networkRuns lists this network's runs newest first, and nothing else")
    void networkRunsListsWhatTheDialogCounts() {
        Network other = networks.save(new Network(project, "Other", 1, false));
        runs.save(new SimulationRun(other, null, "{}"));

        long first = completedRun();
        long second = completedRun();

        assertThat(simulations.networkRuns(network.getId(), OWNER))
                .extracting(SimulationRunDto::id)
                .as("newest first — findByNetworkIdOrderByIdDesc")
                .containsExactly(second, first);
    }

    // -------------------------------------------------------------------------- fixtures

    private long completedRun() {
        return run(SimulationStatus.DONE);
    }

    private long run(SimulationStatus status) {
        return runs.save(newRun(status)).getId();
    }

    private SimulationRun newRun(SimulationStatus status) {
        SimulationRun run = new SimulationRun(network, null, "{}");
        run.setStatus(status);
        if (status != SimulationStatus.QUEUED) {
            run.setStartedAt(Instant.now());
        }
        if (!status.isActive()) {
            run.setFinishedAt(Instant.now());
        }
        return run;
    }

    /**
     * A DONE run with one row in each of the four tables that cascade from it — two metric rows,
     * three periods of the network curve, and three periods of one node's and one link's series.
     *
     * <p>Node and link ids are invented rather than real, which is the convention those tables
     * already follow: element ids are plain {@code BIGINT}s with no foreign key, because a results
     * row must not be a reason an element cannot be deleted ({@code V9}).
     */
    private long completedRunWithResults() {
        SimulationRun run = runs.save(newRun(SimulationStatus.DONE));

        metricResults.save(new MetricResult(network, run, "FILL_RATE", MetricScope.NETWORK, null,
                0.94));
        metricResults.save(new MetricResult(network, run, "TOTAL_COST", MetricScope.NETWORK, null,
                6315.0));

        List<RunTimeseries> curve = new ArrayList<>();
        List<RunNodeTimeseries> nodeRows = new ArrayList<>();
        List<RunLinkTimeseries> linkRows = new ArrayList<>();
        for (int period = 0; period < 3; period++) {
            RunTimeseries point = new RunTimeseries(run, period);
            point.setTotalDemand(50);
            point.setServedDemand(50);
            curve.add(point);

            nodeRows.add(new RunNodeTimeseries(run, 7L, period));
            linkRows.add(new RunLinkTimeseries(run, 9L, period));
        }
        timeseries.saveAll(curve);
        nodeTimeseries.saveAll(nodeRows);
        linkTimeseries.saveAll(linkRows);

        return run.getId();
    }
}
