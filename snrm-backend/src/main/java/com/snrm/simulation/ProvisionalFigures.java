package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a running simulation knows so far — the {@code partial} object on the job poll
 * (FR-17, running from the editor).
 *
 * <p>A hundred-replication run used to report nothing but a percentage for its whole duration; this
 * is what turns the wait into something a researcher can watch. Three constraints keep it honest and
 * cheap, and all three are load-bearing:
 *
 * <p><strong>Streaming statistics only, never the suite.</strong> Recomputing the metric suite after
 * each replication would run every calculator over every trace N times. These figures are running
 * means over exactly what {@code MonteCarloRunner} already holds when a replication completes —
 * its trace — and cost one addition each. Anything that needs the whole replication set is absent by
 * construction, {@code CVAR_COST} above all: a tail expectation over a partial sample is not a
 * provisional value of the final number, it is a different number.
 *
 * <p><strong>No confidence intervals.</strong> An interval over {@code k} of {@code N} replications
 * describes a sample nobody asked about; the resolved request named {@code N}.
 *
 * <p><strong>Superseded, never merged.</strong> On completion the persisted suite is authoritative
 * and the job framework discards this object ({@code JobService}). A client must label these
 * figures provisional and must not persist or export them — presenting them as results would invite
 * a conclusion drawn from twelve replications of a hundred.
 *
 * <p>The three metric-shaped fields mirror their calculators' definitions over the completed
 * disrupted replications: {@code fillRate} is the mean of per-replication ratios of sums
 * ({@code FillRateCalculator}), {@code minFillRate} the mean worst demand-carrying period
 * ({@code MinFillRateCalculator}), {@code totalCost} the mean five-component sum
 * ({@code TotalCostCalculator}). Mirroring matters: a provisional figure computed differently from
 * the number that replaces it would appear to "jump" at completion for no reason a researcher could
 * see.
 *
 * @param replicationsDone  replications completed so far, both sets — the progress numerator
 * @param replicationsTotal replications the job will execute ({@code 2N} paired, {@code N} for a
 *                          baseline run)
 * @param fillRate          running mean fill rate over completed disrupted replications, or null
 *                          before the first one lands
 * @param minFillRate       running mean worst-period fill rate, or null before the first disrupted
 *                          replication with a demand-carrying period
 * @param totalCost         running mean total cost over completed disrupted replications, or null
 */
@Schema(name = "ProvisionalFigures",
        description = "Streaming figures of a running simulation (FR-17). Provisional by "
                + "definition: a partial sample, replaced by the persisted suite on completion and "
                + "never to be persisted or exported. No confidence intervals — an interval over k "
                + "of N replications describes a sample nobody asked about.")
public record ProvisionalFigures(

        @Schema(description = "Replications completed so far, across both sets.", example = "37")
        int replicationsDone,

        @Schema(description = "Replications the job will execute in total.", example = "200")
        int replicationsTotal,

        @Schema(description = "Running mean fill rate over the completed disrupted replications; "
                + "null before the first completes.", example = "0.93")
        Double fillRate,

        @Schema(description = "Running mean worst-period fill rate; null before the first "
                + "disrupted replication with a demand-carrying period completes.", example = "0.71")
        Double minFillRate,

        @Schema(description = "Running mean total cost over the completed disrupted replications; "
                + "null before the first completes.", example = "6315.0")
        Double totalCost) {
}
