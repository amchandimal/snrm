package com.snrm.simulation;

import com.snrm.common.AuditableEntity;
import com.snrm.network.Network;
import com.snrm.scenario.DisruptionScenario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One evaluation of a network against a disruption scenario ({@code SIMULATION_RUN}).
 *
 * <p>A run is the unit that freezes its network: from the moment one exists in a
 * {@linkplain SimulationStatus#isNetworkLocking() locking} state, edits to that network are
 * refused and must fork a variant instead (enforced by
 * {@link com.snrm.network.NetworkMutationGuard}).
 *
 * <p>{@link #getParamsJson()} carries the fully resolved parameter set — horizon, replication
 * count, and the seed actually used — so a completed run can be replayed exactly. The ER model
 * gives the run no separate seed column for that reason: {@code disruption_scenario.seed} is the
 * configured default, the run records what was drawn.
 *
 * <p>Persistence lives in this module alongside the engine, but the two do not mix: the engine
 * reads only the immutable {@code NetworkGraph} snapshot and returns value objects, and this entity
 * is written at the module edge.
 */
@Entity
@Table(name = "simulation_run")
public class SimulationRun extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "network_id", nullable = false)
    private Network network;

    /**
     * The scenario this run applied, or <strong>null for a baseline run</strong> — the undisrupted
     * evaluation of FR-17 ({@code V8__baseline_run.sql}).
     *
     * <p>Null means exactly one thing: the run executed with no {@code ScenarioPlan}, skipped the
     * paired baseline set (there was no disruption to isolate), and its suite carries no
     * disruption-relative rows. A reserved "no disruption" scenario row was rejected in the
     * migration's header: a NULL cannot be edited into meaning something else.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id")
    private DisruptionScenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "ENUM('QUEUED','RUNNING','DONE','FAILED','CANCELLED')")
    private SimulationStatus status = SimulationStatus.QUEUED;

    @Column(name = "started_at", columnDefinition = "DATETIME(6)")
    private Instant startedAt;

    @Column(name = "finished_at", columnDefinition = "DATETIME(6)")
    private Instant finishedAt;

    /**
     * Resolved run parameters as JSON text. Mapped as {@code String} against a MySQL {@code JSON}
     * column, so the driver rejects malformed JSON on write while the persistence layer stays
     * agnostic of the parameter schema.
     */
    @Column(name = "params_json", nullable = false, columnDefinition = "JSON")
    private String paramsJson;

    /**
     * When this run was restored from a project archive, or null if this instance computed it
     * (reproducibility, {@code V7__run_provenance.sql}).
     *
     * <p>Non-null is the whole of the claim: <em>these numbers were produced somewhere else.</em>
     * A restored run is a real {@link SimulationStatus#DONE} run — it freezes its network and it
     * appears in the comparison view, both of which are correct — so the only thing
     * distinguishing it from a locally computed result is this column. Read it before putting a
     * restored result beside a fresh one; the engine that produced it is in
     * {@link #getParamsJson()}, and need not be this one.
     */
    @Column(name = "imported_at", columnDefinition = "DATETIME(6)")
    private Instant importedAt;

    /**
     * The id this run had in the instance that computed it; null for a locally computed run.
     *
     * <p>Carried so a thesis that cites "run 42" stays navigable after the archive is restored into
     * an instance where 42 means something else. Deliberately not a foreign key and deliberately not
     * this row's id — see {@code V7__run_provenance.sql} on why identity is re-created rather than
     * preserved.
     */
    @Column(name = "source_run_id")
    private Long sourceRunId;

    protected SimulationRun() {
        // for JPA
    }

    /** @param scenario the scenario applied, or null for a baseline run (FR-17) */
    public SimulationRun(Network network, DisruptionScenario scenario, String paramsJson) {
        this.network = network;
        this.scenario = scenario;
        this.paramsJson = paramsJson;
    }

    public Long getId() {
        return id;
    }

    public Network getNetwork() {
        return network;
    }

    /** The scenario applied, or null for a baseline run (FR-17). */
    public DisruptionScenario getScenario() {
        return scenario;
    }

    /** Whether this is the undisrupted baseline run of FR-17 — no scenario at all. */
    public boolean isBaselineRun() {
        return scenario == null;
    }

    public SimulationStatus getStatus() {
        return status;
    }

    public void setStatus(SimulationStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    /** When this run was restored from an archive, or null if this instance computed it. */
    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    /** The id this run held in the instance that computed it, or null. */
    public Long getSourceRunId() {
        return sourceRunId;
    }

    public void setSourceRunId(Long sourceRunId) {
        this.sourceRunId = sourceRunId;
    }

    /** Whether these results were computed elsewhere and restored. */
    public boolean isImported() {
        return importedAt != null;
    }
}
