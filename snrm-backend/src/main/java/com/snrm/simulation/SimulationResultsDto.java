package com.snrm.simulation;

import com.snrm.metrics.MetricResultDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A completed run, whole — the answer to {@code GET /api/v1/simulations/{runId}}.
 *
 * <blockquote>{@code GET /api/v1/simulations/{runId} -> full results (metrics + time series)}
 * </blockquote>
 *
 * <p>The two halves are also served separately, as {@code /simulations/{runId}/results} and
 * {@code /simulations/{runId}/timeseries}, and both exist. The split is not redundancy: the metric
 * suite is a dozen rows and the time series is one row per period, so a comparison view listing
 * eight variants wants the first and not the second, while a curve being redrawn on a zoom wants the
 * second and not the first. This object is for the third case — opening one run and seeing
 * everything about it.
 *
 * <p><strong>Available before the run finishes.</strong> A {@code QUEUED} or {@code RUNNING} run
 * answers with its record and two empty lists rather than a 404, because the results view
 * opens on a run the moment it is submitted and polls the job beside it.
 *
 * @param run        the run's own record, including the parameters that produced it
 * @param metrics    the simulated metric suite with its 95% intervals; empty until the
 *                   run completes
 * @param timeseries the per-period curves, disrupted and baseline; empty until the run completes
 */
@Schema(name = "SimulationResults",
        description = "A run with its metric suite and its per-period curves. Both lists "
                + "are empty while the run is still queued or executing.")
public record SimulationResultsDto(

        @Schema(description = "The run itself.")
        SimulationRunDto run,

        @Schema(description = "The eleven simulated metrics, aggregated to a mean and a "
                + "95% confidence interval across replications. TTR carries a "
                + "`displayUnit` — it is the only time-valued metric in the suite. "
                + "AVG_INVENTORY and AVG_PIPELINE are NEUTRAL: the comparison view shows them "
                + "with no winner highlighted (FR-19).")
        List<MetricResultDto> metrics,

        @Schema(description = "One point per period: the disrupted curve and the undisrupted "
                + "baseline curve, averaged across replications. The area between them is "
                + "LOSS_AREA.")
        List<RunTimeseriesDto> timeseries) {
}
