package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The {@code params} object of {@code POST /simulations}.
 *
 * <p><strong>Every field is optional</strong>, and null means "not stated" rather than "zero". The
 * scenario already carries a replication count and a seed and the network already carries
 * a horizon; this object is for the run that wants to differ from them — a quick
 * ten-replication look before committing to a hundred, a re-run of a stored seed, a sensitivity
 * check with demand noise switched on. {@link SimulationParams#resolve} applies the precedence
 * request → scenario → default and records the answer on the run, so what actually ran is never in
 * doubt.
 *
 * <p>The horizon is deliberately absent: a run evaluates the network as it stands, and the horizon
 * is part of the network (FR-13).
 */
@Schema(name = "SimulationParams",
        description = "Optional overrides for one run. Anything omitted falls back to the "
                + "scenario's setting, then to the default.")
public record SimulationParamsDto(

        @Schema(description = "Disrupted replications to run. The undisrupted baseline set "
                + "adds the same number again, so the job executes twice this many. "
                + "Defaults to the scenario's `numReplications`, then to 100.", example = "100")
        @Min(value = 1, message = "replications must be at least 1")
        @Max(value = SimulationParams.MAX_REPLICATIONS,
                message = "replications must not exceed " + SimulationParams.MAX_REPLICATIONS)
        Integer replications,

        @Schema(description = "Base RNG seed. Defaults to the scenario's seed; if that is null too, "
                + "one is drawn and recorded on the run. Re-submitting a completed run's seed with "
                + "the same parameters reproduces it exactly.", example = "20260802")
        Long seed,

        @Schema(description = "Coefficient of variation of the multiplicative demand noise. "
                + "0 — the default — makes demand exactly deterministic, which is what "
                + "lets a run be checked by hand.", example = "0")
        @PositiveOrZero(message = "demandNoiseCv must not be negative")
        Double demandNoiseCv,

        @Schema(description = "Maximum whole-period perturbation of each event's start and "
                + "duration, drawn uniformly from [-j, +j]. 0 by default.", example = "0")
        @PositiveOrZero(message = "timingJitterPeriods must not be negative")
        Integer timingJitterPeriods,

        @Schema(description = "Whether per-period `failure_prob` outages apply at all. "
                + "True by default; a network whose nodes all have failure_prob 0 is unaffected "
                + "either way.", example = "true")
        Boolean includeRandomFailures,

        @Schema(description = "Whether the undisrupted baseline set also drops random failures. "
                + "False by default, so random outages — a property of the network rather than of "
                + "the scenario — appear in both sets and DISRUPTION_COST_DELTA isolates the "
                + "scenario. Set true to compare against a network in which nothing at all goes "
                + "wrong.", example = "false")
        Boolean baselineSuppressesFailures,

        @Schema(description = "What a unit of safety-stock shortfall is worth relative to a unit of "
                + "unmet customer demand, in [0,1). 0.1 by default: enough to pre-position stock "
                + "with spare capacity, never enough to prefer stock over a customer. 0 switches "
                + "replenishment off entirely, which is what a hand-checked run wants.",
                example = "0.1")
        @PositiveOrZero(message = "safetyStockPriority must not be negative")
        Double safetyStockPriority,

        @Schema(description = "Cost of one unit of unmet demand. Omit to price it from the product "
                + "the customer wanted (`product.unit_value`, demand-weighted where a customer "
                + "wants several). This is the accounting price only — it never decides the "
                + "routing.", example = "null")
        @PositiveOrZero(message = "unmetDemandPenalty must not be negative")
        Double unmetDemandPenalty,

        @Schema(description = "Fixed-point resolution of the flow solve: quantities are carried to "
                + "1/quantum of a unit, because the minimum-cost flow takes integer capacities. "
                + "1000 by default, and coarsened automatically on a network large enough to "
                + "overflow.", example = "1000")
        @Min(value = 1, message = "quantum must be at least 1")
        Integer quantum,

        @Schema(description = "Whether to record the per-element time series — one row "
                + "per (node, period) and per (link, period), which is what the playback view of "
                + "FR-18 draws. True by default. Recording changes no simulated number; it costs "
                + "storage roughly `horizon × (nodes + links)` rows per run and a fold per "
                + "replication, so set it false for a batch of runs whose results are only ever "
                + "read as metrics.", example = "true")
        Boolean recordElementTimeseries) {

    /** Nothing stated — the shape of an omitted {@code params} object. */
    public static SimulationParamsDto empty() {
        return new SimulationParamsDto(null, null, null, null, null, null, null, null, null, null);
    }
}
