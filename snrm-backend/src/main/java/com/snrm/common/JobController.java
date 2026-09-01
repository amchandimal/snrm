package com.snrm.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two job endpoints.
 *
 * <blockquote><pre>
 * GET    /api/v1/jobs/{jobId}  -&gt; {status, progress: 0..1, message}
 * DELETE /api/v1/jobs/{jobId}  -&gt; cooperative cancellation
 * </pre></blockquote>
 *
 * <p>Two, and not a third. There is no {@code GET /jobs} listing: nothing calls for one, a
 * client already holds the ids it submitted, and an endpoint enumerating other callers' work would
 * be the first thing multi-user hardening has to take away again.
 *
 * <p>Submission does not live here either. A job is always submitted through the resource it
 * produces — {@code POST /simulations} today, {@code POST /configurations/search} in Phase 2 — because
 * only that endpoint can validate the request before accepting it.
 */
@Tag(name = "Jobs",
        description = "Polling and cancellation for long computations. Jobs are "
                + "submitted through the resource that produces them, e.g. POST /simulations.")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class JobController {

    private final JobService jobs;

    JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "Poll a job's status and progress",
            description = """
                    Answers with the job's state, the completed fraction and the phase it is in \
                    Poll until `status` is one of `DONE`, `FAILED` or `CANCELLED`.

                    **Where the answer is.** `resourceId` carries the row the job is producing — for \
                    a simulation, the `simulation_run.id` — and is set from the moment of \
                    submission, so a results view can open on a run that is still executing.

                    **Job state is in memory and bounded.** The most recent jobs are retained \
                    (`snrm.jobs.retention`) and nothing survives a restart, so a 404 here does not \
                    mean the work was lost: a completed run and its metrics are durable. \
                    Read them from `GET /simulations/{runId}` instead.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The job's current state.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = JobStatusDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such job, or it has been evicted from the retention window.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/jobs/{jobId}")
    public JobStatusDto status(
            @Parameter(description = "Job id returned at submission.",
                    example = "9f1c0b3a-1d2e-4f5a-8b7c-6d5e4f3a2b1c")
            @PathVariable String jobId) {
        return JobStatusDto.of(jobs.require(jobId));
    }

    @Operation(summary = "Cancel a job",
            description = """
                    Cooperative cancellation. A job that has not started is dropped from \
                    the queue outright; a running one is *asked* to stop and does so at its next \
                    safe point — for a simulation, between replications and between periods.

                    Nothing is interrupted. A worker killed between writing a run's metric rows and \
                    writing its status would leave a half-persisted result that looks complete, and \
                    completed runs are immutable precisely so that cannot happen. The \
                    cost is that cancellation is not instantaneous; the run reaches `CANCELLED` \
                    within one replication.

                    A cancelled simulation run holds nothing frozen, so its network becomes \
                    editable again.

                    **Idempotent.** Cancelling a job that has already finished answers 200 with \
                    `cancelled: false` rather than an error — the UI's stop button should not have \
                    to win a race to be correct.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Cancellation requested, or the job was already terminal.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = JobStatusDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such job.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/api/v1/jobs/{jobId}")
    public ResponseEntity<JobStatusDto> cancel(
            @Parameter(description = "Job id returned at submission.",
                    example = "9f1c0b3a-1d2e-4f5a-8b7c-6d5e4f3a2b1c")
            @PathVariable String jobId) {
        jobs.cancel(jobId);
        // The state after the request, not a bare 204: a caller that asked a running job to stop
        // wants to know whether it is already CANCELLED or still winding down.
        return ResponseEntity.ok(JobStatusDto.of(jobs.require(jobId)));
    }
}
