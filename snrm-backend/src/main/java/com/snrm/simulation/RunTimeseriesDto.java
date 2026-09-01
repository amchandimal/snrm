package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One period of a run's performance curve — a row of {@code GET /simulations/{runId}/timeseries}
 * ({@code RUN_TIMESERIES}).
 *
 * <p>Replication averages, not one replication's numbers: the table stores per-period
 * aggregates as replication averages. So {@link #servedDemand} is the mean across the disrupted
 * set and {@link #baselineServedDemand} the mean across the paired undisrupted one.
 *
 * <p><strong>Both curves are here, and that is what makes the resilience triangle drawable.</strong>
 * {@code LOSS_AREA} is the area between them; a client holding only the disrupted curve
 * could render the number but not the shape. See {@code V6__run_timeseries_baseline.sql} for why the
 * ER model had to be amended to carry it.
 *
 * <p>There is no baseline {@code totalDemand} because it would always equal {@link #totalDemand}:
 * the paired replications share their demand realisations by construction. Baseline fill
 * rate is {@code baselineServedDemand / totalDemand}.
 *
 * @param period               0-based index within the horizon
 * @param totalDemand          mean demand realised in this period
 * @param servedDemand         mean demand met, across the disrupted replications
 * @param cost                 mean total cost of the period, across the disrupted replications
 * @param baselineServedDemand mean demand met in the same period of the undisrupted baseline set
 * @param baselineCost         mean cost of the same period of the undisrupted baseline set
 * @param endingInventory      mean on-hand stock across the whole network at the end of the period
 * @param inPipeline           mean material in transit or in processing dwell at the end of the
 *                             period — shipped but not yet anywhere
 */
@Schema(name = "RunTimeseriesPoint",
        description = "One period of a run's performance curve, averaged across replications. "
                + "Carries both the disrupted and the undisrupted baseline curve, which is what "
                + "lets the resilience triangle be drawn without recomputation.")
public record RunTimeseriesDto(

        @Schema(description = "0-based period index.", example = "3")
        int period,

        @Schema(description = "Mean demand realised at customers in this period.", example = "50.0")
        double totalDemand,

        @Schema(description = "Mean demand met, across the disrupted replications.", example = "40.0")
        double servedDemand,

        @Schema(description = "Mean total cost of the period — fixed, variable, transport, holding "
                + "and shortage.", example = "780.0")
        double cost,

        @Schema(description = "Mean demand met in the same period of the undisrupted baseline set.",
                example = "50.0")
        double baselineServedDemand,

        @Schema(description = "Mean cost of the same period of the undisrupted baseline set.",
                example = "650.0")
        double baselineCost,

        @Schema(description = "Mean on-hand stock across the whole network at the end of the "
                + "period. The total the per-element `onHand` series sums to.",
                example = "40.0")
        double endingInventory,

        @Schema(description = "Mean material in transit or in processing dwell at the end of the "
                + "period — shipped and paid for but not yet anywhere. Zero on a network "
                + "whose lead times are all 0.", example = "20.0")
        double inPipeline) {

    /** The entity as the API returns it. */
    public static RunTimeseriesDto of(RunTimeseries row) {
        return new RunTimeseriesDto(row.getPeriod(), row.getTotalDemand(), row.getServedDemand(),
                row.getCost(), row.getBaselineServedDemand(), row.getBaselineCost(),
                row.getEndingInventory(), row.getInPipeline());
    }

    /** Served over total demand in this period; 1 where nothing was demanded. */
    public double fillRate() {
        return totalDemand <= 0 ? 1 : servedDemand / totalDemand;
    }
}
