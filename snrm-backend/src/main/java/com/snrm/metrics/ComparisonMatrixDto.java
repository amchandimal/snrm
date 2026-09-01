package com.snrm.metrics;

import com.snrm.common.TimeUnit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The cross-variant metric matrix — the answer to
 * {@code GET /api/v1/projects/{id}/comparison?networkIds=…} (FR-10).
 *
 * <blockquote>"Comparison view — variants × metrics matrix with best-in-row highlighting, radar
 * chart of normalised metrics, and lever-change annotations from {@code lever_changes_json}."
 * </blockquote>
 *
 * <p>{@link #variants} are the columns and {@link #rows} the rows, with each row's
 * {@link ComparisonRowDto#cells()} aligned by index against the variant list. Everything the view
 * needs is in this one response: the values, the winner of each row, the lever diff that explains
 * each column, and the caveats.
 *
 * <h2>Three things are decided here rather than left to the client</h2>
 *
 * <p><strong>The winner of each row.</strong> Which way is better comes from the calculator
 * and ties are common, so deciding it once means the matrix on screen and the matrix in an
 * exported spreadsheet cannot disagree.
 *
 * <p><strong>The common unit.</strong> Comparison across configurations converts to a common unit
 * before display, and the conversion needs each variant's period
 * length — which the server has and a client would have to fetch per column. {@link #timeUnit} is
 * the finest period unit among the compared variants, so converting into it never rounds a fine
 * variant's value away.
 *
 * <p><strong>The caveats.</strong> {@link #mixedTimeBases} and {@link #mixedScenarios} are the two
 * ways a comparison can be arithmetically correct and still misleading, and both are invisible in
 * the numbers. See {@link ComparisonNoteDto}.
 *
 * @param projectId      the project every compared network belongs to
 * @param variants       the columns, in the order requested
 * @param rows           the metric rows, in suite order
 * @param timeUnit       the unit every time-valued row was converted to; null if no such row exists
 * @param mixedTimeBases whether the compared variants disagree about period length
 * @param mixedScenarios whether the runs behind the columns applied different scenarios
 * @param notes          the caveats above, as codes plus prose
 */
@Schema(name = "ComparisonMatrix",
        description = "Variants × metrics, with best-in-row already decided, time-valued metrics "
                + "already in a common unit, and the lever diff on each column.")
public record ComparisonMatrixDto(

        @Schema(description = "The project the compared networks belong to.", example = "1")
        Long projectId,

        @Schema(description = "The columns, in the order the request named them.")
        List<ComparisonVariantDto> variants,

        @Schema(description = "The rows, in the order the metric registry emits them — topological "
                + "first, then simulated. Network-scoped metrics only: NODE_CRITICALITY "
                + "is one row per node and belongs to the results dashboard's table, not here.")
        List<ComparisonRowDto> rows,

        @Schema(description = "The unit every time-valued row was converted to — the finest period "
                + "unit among the compared variants. Null when no compared metric is "
                + "time-valued.", example = "DAY", nullable = true)
        TimeUnit timeUnit,

        @Schema(description = "True when the compared configurations do not share a period length. "
                + "The values are still comparable — they have been converted — but the models "
                + "differ in what they can resolve.", example = "false")
        boolean mixedTimeBases,

        @Schema(description = "True when the runs behind the columns applied different disruption "
                + "scenarios, so the comparison measures the scenarios as well as the "
                + "configurations. Pin it with `scenarioId`.", example = "false")
        boolean mixedScenarios,

        @Schema(description = "Caveats to show above the matrix; empty when there are none.")
        List<ComparisonNoteDto> notes) {
}
