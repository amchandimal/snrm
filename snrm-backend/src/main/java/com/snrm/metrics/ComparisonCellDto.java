package com.snrm.metrics;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One cell of the comparison matrix: what one variant scored on one metric.
 *
 * <p>A cell is absent — {@code null} in {@link ComparisonRowDto#cells()} — where the metric has no
 * value for that variant, which happens for a real reason and not by accident: a variant with no
 * completed run has no simulated metrics at all, and {@code TTR} has nothing to report on a run in
 * which no replication was disrupted. Rendering a missing cell as 0 would put the best
 * possible recovery time on a configuration that was never tested.
 *
 * <h2>Time-valued metrics carry two numbers</h2>
 *
 * <p>{@link #value} is in the row's {@link ComparisonRowDto#unit()} — the common unit every variant
 * in the comparison has been converted to — while {@link #periods} is the raw figure the
 * calculator produced, in <em>this</em> variant's periods. Both are needed and neither substitutes
 * for the other: the converted value is what makes two variants comparable when their clocks differ,
 * and the period count is what ties the number back to the run the reader can open.
 *
 * <p>For a dimensionless metric — a fill rate, a cost, an index — {@link #periods} is null and
 * {@link #value} is simply the stored number.
 *
 * @param value  the number, converted to the row's common unit where the metric is time-valued
 * @param ciLow  lower bound of the 95% interval, in the same unit as {@link #value}, or null
 * @param ciHigh upper bound, or null
 * @param periods the raw value in this variant's own periods; null for a dimensionless metric
 * @param best   whether this cell is the best in its row, or tied for it
 */
@Schema(name = "ComparisonCell",
        description = "One variant's value for one metric. Time-valued metrics carry both the "
                + "converted value and the original period count.")
public record ComparisonCellDto(

        @Schema(description = "The value, in the row's unit.", example = "14.0")
        double value,

        @Schema(description = "Lower bound of the 95% CI across replications, in the row's unit. "
                + "Null for a topological metric, which is exact, and for CVAR_COST, which is a "
                + "functional of the whole replication set rather than a mean.",
                example = "12.4", nullable = true)
        Double ciLow,

        @Schema(description = "Upper bound of the 95% CI.", example = "15.6", nullable = true)
        Double ciHigh,

        @Schema(description = "The value in this variant's own periods, before conversion to the "
                + "row's common unit. Null unless the metric is time-valued.",
                example = "14.0", nullable = true)
        Double periods,

        @Schema(description = "True for the best cell in the row, or every cell tied for best. "
                + "Always false in a row whose direction is NEUTRAL.", example = "true")
        boolean best) {

    /** A dimensionless value, before best-in-row is decided. */
    static ComparisonCellDto plain(double value, Double ciLow, Double ciHigh) {
        return new ComparisonCellDto(value, ciLow, ciHigh, null, false);
    }

    /** A time-valued value converted to the row's unit, keeping the original period count. */
    static ComparisonCellDto timed(double value, Double ciLow, Double ciHigh, double periods) {
        return new ComparisonCellDto(value, ciLow, ciHigh, periods, false);
    }

    /** The same cell, marked as a winner. */
    ComparisonCellDto asBest() {
        return new ComparisonCellDto(value, ciLow, ciHigh, periods, true);
    }
}
