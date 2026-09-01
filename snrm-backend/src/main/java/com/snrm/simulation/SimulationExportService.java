package com.snrm.simulation;

import com.snrm.common.DurationAmount;
import com.snrm.common.TabularExport;
import com.snrm.common.TabularExport.Table;
import com.snrm.common.TabularFormat;
import com.snrm.common.TimeUnit;
import com.snrm.metrics.MetricResult;
import com.snrm.metrics.MetricResultRepository;
import com.snrm.network.Network;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes one simulation run out as a spreadsheet — the results dashboard's export button
 * (FR-08).
 *
 * <p>Three tables, because the dashboard shows three things and a researcher who exports it wants
 * all of them: the run's own record with the parameters that produced it, the metric suite with its
 * confidence intervals, and the per-period curves. What they are for differs — the first is
 * provenance, the second is the result, the third is the data behind the figure — which is why they
 * are three tables and not one wide one.
 *
 * <h2>The curve table carries the triangle, not just the curve</h2>
 *
 * <p>{@code timeseries} writes the fill rate, the baseline fill rate <em>and the gap between
 * them</em>, period by period — which is what the shaded area is drawn from, so the
 * figure becomes checkable rather than merely drawn.
 *
 * <p><strong>That column does not sum to {@code LOSS_AREA}, and the difference is worth stating.</strong>
 * The metric is {@code mean_r Σ_t max(0, b_r(t) − d_r(t))} — the shortfall taken per replication and
 * then averaged (see {@code LossAreaCalculator}). This table holds replication <em>averages</em>,
 * so summing its gap column computes {@code Σ_t max(0, mean_r b_r(t) − mean_r d_r(t))}:
 * the same quantity with the mean and the {@code max(0, …)} in the other order. Since
 * {@code max(0, …)} is convex, the sum of this column is a <em>lower bound</em> on {@code LOSS_AREA},
 * and the two coincide exactly on a deterministic run — which is the one every worked example in
 * {@code docs/simulation-verification.md} uses. {@code cost_delta} carries no such caveat: it is
 * linear, so its column sums to {@code DISRUPTION_COST_DELTA} exactly.
 *
 * <h2>Time-valued metrics are written twice</h2>
 *
 * <p>`TTR` is stored as a count of periods with the network's period unit as its {@code
 * display_unit}, and a bare 14 in a spreadsheet is unreadable a month later. The metrics
 * table therefore writes both {@code value} (periods) and {@code value_in_display_unit} with its
 * unit beside it — computed as {@code periods × periodValue}, not by reading the unit alone: a
 * network stepping in 2 DAY has a display unit of DAY, and 14 of its periods are 28 days.
 */
@Service
public class SimulationExportService {

    /** What an export is called: {@code Baseline-v1-run12.xlsx}. */
    private static final String FILENAME_PATTERN = "%s-v%d-run%d.%s";

    private final SimulationRunRepository runs;
    private final MetricResultRepository metricResults;
    private final RunTimeseriesRepository timeseries;

    SimulationExportService(SimulationRunRepository runs, MetricResultRepository metricResults,
            RunTimeseriesRepository timeseries) {
        this.runs = runs;
        this.metricResults = metricResults;
        this.timeseries = timeseries;
    }

    /**
     * Exports a run.
     *
     * @param runId   the run to write
     * @param ownerId the calling researcher
     * @param format  workbook or zipped CSV set
     * @throws EntityNotFoundException if the run is not the caller's
     */
    @Transactional(readOnly = true)
    public TabularExport.File export(long runId, long ownerId, TabularFormat format) {
        SimulationRun run = runs.findById(runId)
                .filter(found -> found.getNetwork().getProject().getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No simulation run with id " + runId));

        Network network = run.getNetwork();
        List<Table> tables = List.of(
                runTable(run, network),
                metricsTable(runId, network.getPeriodLength()),
                timeseriesTable(runId));

        byte[] content = switch (format) {
            case XLSX -> TabularExport.workbook(tables);
            case CSV -> TabularExport.zippedCsv(tables);
        };
        String filename = FILENAME_PATTERN.formatted(TabularExport.safeName(network.getName()),
                network.getVersion(), runId, format.extension());
        return new TabularExport.File(filename, format.contentType(), content);
    }

    // ----------------------------------------------------------------------------- run

    /**
     * The run's provenance: what was evaluated, against what, with which parameters.
     *
     * <p>Written as one row of many columns rather than as key/value pairs, so that exports of
     * several runs concatenate into a table a researcher can sort. The seed is here because it is
     * the whole of the reproducibility claim — an exported result whose seed is missing
     * cannot be re-derived.
     */
    private static Table runTable(SimulationRun run, Network network) {
        DurationAmount period = network.getPeriodLength();
        List<String> headers = List.of(
                "run_id", "network_id", "network_name", "network_version", "scenario_id",
                "scenario_name", "status", "started_at", "finished_at",
                "period_length_value", "period_length_unit", "horizon_periods",
                "params_json");

        List<String> row = List.of(
                String.valueOf(run.getId()),
                String.valueOf(network.getId()),
                network.getName(),
                String.valueOf(network.getVersion()),
                // Blank cells, not "null": a baseline run (FR-17) applied no scenario, and a
                // spreadsheet reader filters blanks where a literal "null" becomes a category.
                run.getScenario() == null ? "" : String.valueOf(run.getScenario().getId()),
                run.getScenario() == null ? "" : run.getScenario().getName(),
                run.getStatus().name(),
                TabularExport.instant(run.getStartedAt()),
                TabularExport.instant(run.getFinishedAt()),
                TabularExport.number(period.getValue()),
                period.getUnit().name(),
                String.valueOf(network.getHorizonPeriods()),
                // Verbatim, not unpacked into columns: it is the exact document the run replays
                // from, and a column per parameter would go stale the moment one is added.
                TabularExport.text(run.getParamsJson()));

        return new Table("run", headers, List.of(row));
    }

    // ------------------------------------------------------------------------- metrics

    private Table metricsTable(long runId, DurationAmount period) {
        List<String> headers = List.of(
                "metric_code", "scope", "scope_id", "value", "ci_low", "ci_high",
                "display_unit", "value_in_display_unit");

        List<MetricResult> results = metricResults.findByRunId(runId);
        List<List<String>> rows = new ArrayList<>(results.size());
        for (MetricResult result : results) {
            rows.add(List.of(
                    result.getMetricCode(),
                    result.getScope().name(),
                    TabularExport.text(result.getScopeId()),
                    TabularExport.number(result.getValue()),
                    TabularExport.number(result.getCiLow()),
                    TabularExport.number(result.getCiHigh()),
                    result.getDisplayUnit() == null ? "" : result.getDisplayUnit().name(),
                    readable(result, period)));
        }
        return new Table("metrics", headers, rows);
    }

    /**
     * A time-valued metric restated in its display unit — "14 periods" as "14" days, or as 28 on a
     * network stepping in 2 DAY.
     *
     * <p>The multiplication by {@code period.getValue()} is the part that is easy to leave out and
     * wrong to: {@code display_unit} names the unit a period is <em>stated in</em>, not the length of
     * a period, so reading it alone silently divides every figure on a multi-unit network by the
     * period value. Empty for a dimensionless metric, which has nothing to restate.
     */
    private static String readable(MetricResult result, DurationAmount period) {
        TimeUnit unit = result.getDisplayUnit();
        if (unit == null) {
            return "";
        }
        double perPeriod = period.getUnit().secondsOf(period.getValue()) / unit.secondsOf();
        return TabularExport.number(result.getValue() * perPeriod);
    }

    // ---------------------------------------------------------------------- timeseries

    /**
     * The performance curves, and the two gaps that are the metrics behind the figure.
     *
     * <p>{@code fill_rate_loss} is {@code baseline − disrupted} per period: the height of the
     * resilience triangle at that period, and the integrand of `LOSS_AREA`. {@code
     * cost_delta} is the same for cost and sums to `DISRUPTION_COST_DELTA`. Writing them out is what
     * lets a reader check the shaded area against the numbers instead of taking the
     * drawing's word for it.
     */
    private Table timeseriesTable(long runId) {
        List<String> headers = List.of(
                "period", "total_demand", "served_demand", "fill_rate",
                "baseline_served_demand", "baseline_fill_rate", "fill_rate_loss",
                "cost", "baseline_cost", "cost_delta");

        List<RunTimeseries> series = timeseries.findSeries(runId);
        List<List<String>> rows = new ArrayList<>(series.size());
        for (RunTimeseries point : series) {
            double total = point.getTotalDemand();
            // A period with no demand is fully served by definition, matching RunTimeseriesDto and
            // the FILL_RATE calculator; 0/0 would otherwise print as NaN in a spreadsheet.
            double fill = total <= 0 ? 1 : point.getServedDemand() / total;
            double baselineFill = total <= 0 ? 1 : point.getBaselineServedDemand() / total;
            rows.add(List.of(
                    String.valueOf(point.getPeriod()),
                    TabularExport.number(total),
                    TabularExport.number(point.getServedDemand()),
                    TabularExport.number(fill),
                    TabularExport.number(point.getBaselineServedDemand()),
                    TabularExport.number(baselineFill),
                    TabularExport.number(baselineFill - fill),
                    TabularExport.number(point.getCost()),
                    TabularExport.number(point.getBaselineCost()),
                    TabularExport.number(point.getCost() - point.getBaselineCost())));
        }
        return new Table("timeseries", headers, rows);
    }
}
