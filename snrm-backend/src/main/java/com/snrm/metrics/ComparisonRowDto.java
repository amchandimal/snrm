package com.snrm.metrics;

import com.snrm.common.TimeUnit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One row of the comparison matrix: one metric across every variant.
 *
 * <blockquote>"variants × metrics matrix with best-in-row highlighting"</blockquote>
 *
 * <p>{@link #cells} is aligned by index with {@link ComparisonMatrixDto#variants()}, with a null
 * where the variant has no value for this metric. Alignment rather than a map keyed by network id
 * because the matrix is rendered as a table and a table has columns; a client that had to look each
 * cell up would be reimplementing the alignment the server already did.
 *
 * <h2>Best-in-row is decided here, not in the client</h2>
 *
 * <p>Which cell wins depends on {@link #direction}, which is the calculator's own declaration
 * — and on ties, which are common in a suite full of bounded ratios. Deciding it once,
 * beside the direction, is what stops the matrix and an exported copy of the matrix from disagreeing
 * about the winner. A {@link MetricDirection#NEUTRAL} row has no winner at all and every cell comes
 * back with {@code best: false}; see that enum on why that is a decision rather than a gap.
 *
 * <h2>Time-valued rows are stated in one unit</h2>
 *
 * <p>{@link #unit} is set exactly when {@link #timeValued} is true, and every cell's
 * {@link ComparisonCellDto#value()} has been converted into it. Without that conversion
 * a row comparing a variant clocked in days against one clocked in hours would rank 14 above 20 and
 * be comparing a fortnight with most of a day.
 *
 * @param metricCode the code
 * @param direction  which way is better, from the calculator
 * @param timeValued whether the metric counts periods and so needs a unit to be read
 * @param unit       the common unit every cell was converted to; null unless {@link #timeValued}
 * @param cells      one entry per variant, aligned by index; null where there is no value
 */
@Schema(name = "ComparisonRow",
        description = "One metric across every compared variant, with the winner already decided "
                + "and time-valued values already in a common unit.")
public record ComparisonRowDto(

        @Schema(description = "Metric code from the suite. Opaque: a client should render "
                + "an unfamiliar code rather than reject it.", example = "TTR")
        String metricCode,

        @Schema(description = "HIGHER_IS_BETTER, LOWER_IS_BETTER, or NEUTRAL for a metric that "
                + "describes a configuration without ranking it.", example = "LOWER_IS_BETTER")
        MetricDirection direction,

        @Schema(description = "Whether the value is a count of periods.",
                example = "true")
        boolean timeValued,

        @Schema(description = "The unit every cell in this row has been converted to — the finest "
                + "period unit among the compared variants, so nothing is rounded away. Null for a "
                + "dimensionless metric.", example = "DAY", nullable = true)
        TimeUnit unit,

        @Schema(description = "One cell per variant, in the order of `variants`. Null where the "
                + "variant has no value for this metric — a variant with no completed run, or a "
                + "metric that was undefined for its run.")
        List<ComparisonCellDto> cells) {
}
