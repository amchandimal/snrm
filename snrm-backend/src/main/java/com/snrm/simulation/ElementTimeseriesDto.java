package com.snrm.simulation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The per-element time series of one run — the answer to
 * {@code GET /simulations/{runId}/timeseries/elements} and to its two single-element forms
 * (FR-18).
 *
 * <h2>{@link #available} is the field to branch on, and it is not "is this list empty"</h2>
 *
 * <p>Three different runs answer with empty lists, and a client must not treat them alike:
 *
 * <ul>
 *   <li>a run that is still {@code QUEUED} or {@code RUNNING} — <strong>false</strong>, the same
 *       empty-not-error the other two reads give, so a results view can open on a submitted run and
 *       poll the job beside it;</li>
 *   <li>a run that predates {@code V9__element_timeseries.sql}, or one submitted with
 *       {@code params.recordElementTimeseries: false} — <strong>false</strong>. Nothing was ever
 *       recorded, and a playback view should say so rather than animate a network in which nothing
 *       moves;</li>
 *   <li>a single-element read naming an element this run has no rows for — <strong>true</strong>
 *       with an empty list. The run recorded; that element did not appear in it.</li>
 * </ul>
 *
 * <p>None of them is an error. A run with no element series is a run that answered a different
 * question, not a failure, so this endpoint never 404s on the series and never 500s on an old run.
 *
 * <h2>One envelope for all three endpoints</h2>
 *
 * <p>The single-element reads return this same object with one entry in the relevant list and the
 * other empty, rather than a bare element or a 404. Two reasons: a client that can render the
 * network-wide view can render a drill-down with no second parser and no second model (which is what
 * the front end's "models mirror backend DTOs" rule asks for), and {@code available} keeps exactly
 * one meaning — <em>this run has a per-element series at all</em> — instead of becoming ambiguous
 * between "the run recorded nothing" and "no such element".
 *
 * @param available whether this run recorded a per-element series at all — see the class note
 * @param nodes     one entry per node, or the one asked for
 * @param links     one entry per link, or the one asked for
 */
@Schema(name = "ElementTimeseries",
        description = "Per-element time series of one run. `available` is false — with "
                + "empty lists, never an error — for a run that is not yet DONE, for a run that "
                + "predates the feature, and for one submitted with recordElementTimeseries: "
                + "false. The single-element endpoints return this same envelope with one entry.")
public record ElementTimeseriesDto(

        @Schema(description = "Whether this run recorded a per-element series at all.",
                example = "true")
        boolean available,

        @Schema(description = "Per-node series, in node-id order.")
        List<NodeTimeseriesDto> nodes,

        @Schema(description = "Per-link series, in link-id order.")
        List<LinkTimeseriesDto> links) {

    /** No series: not DONE yet, recorded with the flag off, or a run older than V9. */
    public static ElementTimeseriesDto unavailable() {
        return new ElementTimeseriesDto(false, List.of(), List.of());
    }
}
