package com.snrm.common;

/**
 * What {@link JobService#submit} hands back: the identifier to poll, and the resource the job is
 * producing.
 *
 * <p>Both halves matter to the caller of {@code POST /simulations}, which answers 202 with them
 * together. {@link #jobId} addresses {@code GET /jobs/{jobId}} and {@code DELETE /jobs/{jobId}};
 * {@link #resourceId} is the {@code simulation_run.id} whose results will be at
 * {@code GET /simulations/{runId}} once the job reaches {@link JobStatus#DONE}. The client therefore
 * knows where the answer will appear before the answer exists, which is what lets the results view
 * open on a run that is still executing.
 *
 * @param jobId      opaque identifier, unique for the lifetime of the process
 * @param type       what kind of job this is, e.g. {@code SIMULATION} — for logs and for the UI's
 *                   label, never branched on by the framework
 * @param resourceId the row the job is producing, or null for a job that produces nothing addressable
 */
public record JobHandle(String jobId, String type, Long resourceId) {
}
