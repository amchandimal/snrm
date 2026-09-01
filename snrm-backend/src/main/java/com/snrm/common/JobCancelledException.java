package com.snrm.common;

/**
 * Thrown by a {@link JobTask} that observed {@link ProgressSink#cancelled()} and stopped.
 *
 * <p>Not a {@link DomainException} and never rendered to a client: it is how a task tells
 * {@link JobService} that it stopped on request rather than failed, so the job lands in
 * {@link JobStatus#CANCELLED} with no {@code error} rather than in {@link JobStatus#FAILED} with a
 * misleading one. The distinction is the whole reason cooperative cancellation needs a signal at
 * all — an ordinary early return would be indistinguishable from a completed run that produced
 * nothing.
 *
 * <p>Carries no stack trace: it is control flow along an expected path, thrown from inside a Monte
 * Carlo loop that may have hundreds of virtual threads in flight, and filling in a trace for each of
 * them costs more than the cancellation saves.
 */
public class JobCancelledException extends RuntimeException {

    public JobCancelledException() {
        this("Cancelled at the caller's request.");
    }

    public JobCancelledException(String message) {
        // writableStackTrace = false; see the class note.
        super(message, null, false, false);
    }
}
