package com.snrm.simulation;

import com.snrm.common.DurationDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One simulation run's own record, without its results ({@code SIMULATION_RUN}, at the DTO
 * boundary).
 *
 * <p>{@link #params} is the resolved parameter set read back out of {@code params_json} — the seed
 * that was drawn, the replication count that was used, the engine version that produced it. That is
 * what makes a completed run replayable and is the whole of the reproducibility claim on the
 * simulation side.
 *
 * <p>{@link #unresolvedEventIds} is empty on any run that was accepted, and is carried anyway
 * because it is the shape of a refusal: an event naming a node this network does not have is
 * reported through the same field the submission was rejected on.
 *
 * @param id                 the run
 * @param networkId          the network it evaluated — frozen while this run is in a locking state
 * @param networkName        that network's name, so a results view needs no second fetch
 * @param scenarioId         the scenario it applied
 * @param scenarioName       that scenario's name
 * @param status             {@code QUEUED | RUNNING | DONE | FAILED | CANCELLED}
 * @param startedAt          when the job began executing, or null while queued
 * @param finishedAt         when it reached a terminal state, or null
 * @param periodLength       the evaluated network's period length
 * @param horizonPeriods     how many periods the run covers
 * @param params             the fully resolved parameter set
 * @param importedAt         when this run was restored from a project archive, or null if this
 *                           instance computed it
 * @param sourceRunId        the id it held in the instance that computed it, or null
 * @param unresolvedEventIds scenario events that named nothing in this network; empty on an
 *                           accepted run
 */
@Schema(name = "SimulationRun",
        description = "One evaluation of a network against a disruption scenario.")
public record SimulationRunDto(

        @Schema(description = "Run id.", example = "12")
        Long id,

        @Schema(description = "The network evaluated.", example = "1")
        Long networkId,

        @Schema(description = "That network's name.", example = "Baseline")
        String networkName,

        @Schema(description = "The disruption scenario applied, or null for a baseline run — the "
                + "undisrupted evaluation of FR-17. A baseline run's suite carries no "
                + "disruption-relative rows (TTR, LOSS_AREA, DISRUPTION_COST_DELTA, "
                + "RESILIENCE_INDEX); absent is unmeasured, never zero.", example = "1")
        Long scenarioId,

        @Schema(description = "That scenario's name; null for a baseline run.",
                example = "Plant outage")
        String scenarioName,

        @Schema(description = "QUEUED, RUNNING, DONE, FAILED or CANCELLED. A run in any "
                + "of the first three freezes its network.", example = "DONE")
        SimulationStatus status,

        @Schema(description = "When execution began; null while queued.",
                example = "2026-08-02T09:15:04Z")
        Instant startedAt,

        @Schema(description = "When it reached a terminal state; null otherwise.",
                example = "2026-08-02T09:15:11Z")
        Instant finishedAt,

        @Schema(description = "The evaluated network's period length. Carried on the "
                + "run because nothing in the results can be read without it: the performance "
                + "curve's x-axis is labelled in this unit, and TTR is a count of these periods, "
                + "so \"14\" means fourteen days on one network and fourteen hours on another. "
                + "The network is frozen while this run is locking, so "
                + "the clock recorded here cannot drift from the one that produced the numbers.")
        DurationDto periodLength,

        @Schema(description = "How many periods this run covers — the length of the curve.",
                example = "52")
        int horizonPeriods,

        @Schema(description = "The parameter set actually used, including the seed. Re-submitting "
                + "it reproduces this run exactly.")
        SimulationParams params,

        @Schema(description = "When this run was restored from a project archive, or null if this "
                + "installation computed it. A non-null value means the results beside "
                + "it — metric values and time series — were produced by another instance, "
                + "possibly under a different engine version; `params.engineVersion` names which. "
                + "Read it before placing a restored result next to a freshly computed one.",
                example = "2026-08-04T11:02:41Z")
        Instant importedAt,

        @Schema(description = "The id this run held in the instance that computed it; null unless "
                + "it was restored from an archive. Identity is re-created on import rather than "
                + "preserved, so a citation of the original run number stays resolvable.",
                example = "42")
        Long sourceRunId,

        @Schema(description = "Scenario events that resolved to nothing in this network. Empty on "
                + "any accepted run — the submission is refused if it is not.", example = "[]")
        java.util.List<Long> unresolvedEventIds) {
}
