package com.snrm.common;

import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The asynchronous job framework — submit, poll, cancel.
 *
 * <blockquote><pre>
 * POST   /api/v1/simulations   -&gt; 202 Accepted {jobId}   (validated, queued)
 * GET    /api/v1/jobs/{jobId}  -&gt; {status, progress: 0..1, message}
 * DELETE /api/v1/jobs/{jobId}  -&gt; cooperative cancellation
 * </pre></blockquote>
 *
 * <p><strong>It knows nothing about simulations.</strong> A job is a {@link JobTask} — a function
 * from {@link ProgressSink} to a result — so the Phase 2 configuration search runs
 * through this class unchanged ("Runs through the existing {@code JobService}"), and its
 * {@code ConfigurationStrategy} receives the same {@code ProgressSink} the simulation engine does.
 * Nothing here imports a network, a scenario or a run.
 *
 * <h2>Two levels of bounding, and why</h2>
 *
 * <p><strong>Jobs are bounded; replications are not.</strong> The executor beneath this class holds
 * {@link JobProperties#workers()} platform threads over a queue of
 * {@link JobProperties#queueCapacity()} — a submission past that is refused with
 * {@link JobQueueFullException} rather than accepted and forgotten. Inside a job, the Monte Carlo
 * runner fans out onto virtual threads without asking anyone. That split is deliberate:
 * one simulation job already keeps every core busy, so admitting a second and a third would make all
 * three slower and none of them finish sooner, whereas virtual threads inside one job are exactly
 * the replication parallelism that mitigates the cost of min-cost-flow.
 *
 * <h2>Cancellation</h2>
 *
 * <p>Cancellation is cooperative. {@link #cancel} raises a flag the task observes through
 * {@link ProgressSink#cancelled()}; it also calls {@link Future#cancel(boolean) Future.cancel(false)},
 * which removes a job that has not started from the queue outright. A running job is never
 * interrupted — a worker killed between writing metric rows and writing the run's status would leave
 * a half-persisted result that looks complete, and completed runs are immutable precisely
 * so that cannot happen.
 *
 * <h2>What survives, and what does not</h2>
 *
 * <p>Job state is in memory and bounded by {@link JobProperties#retention()}: finished jobs are
 * evicted oldest-first, and nothing survives a restart. That is the right trade because a job record
 * is not the result — the result is a {@code simulation_run} row and its metrics, which are durable
 * A client that polls a jobId the framework has forgotten gets a 404 and should read the
 * run instead, which is why {@link JobHandle} carries the run id from the moment of submission.
 *
 * <p>Each task runs with its job id in the SLF4J {@link MDC} under {@value #MDC_KEY}, which is
 * the per-job correlation id: every line a replication logs can be traced back to the
 * submission that caused it.
 */
@Service
@EnableConfigurationProperties(JobProperties.class)
public class JobService {

    /** MDC key carrying the job id for the duration of a task. */
    public static final String MDC_KEY = "jobId";

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobProperties properties;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, JobRecord> jobs = new ConcurrentHashMap<>();

    /** Ids of terminated jobs, oldest first — the retention window's eviction order. */
    private final ConcurrentLinkedDeque<String> finished = new ConcurrentLinkedDeque<>();

    JobService(JobProperties properties) {
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(
                properties.workers(), properties.workers(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                namedThreadFactory(),
                refuseWhenFull());
        log.info("Job framework ready: {} worker(s), queue capacity {}, retaining {} finished job(s)",
                properties.workers(), properties.queueCapacity(), properties.retention());
    }

    /**
     * Accepts a task, queues it, and answers immediately.
     *
     * <p>The caller is expected to have validated everything it can <em>before</em> calling: a job
     * that fails on its first line because a network id was wrong is a 202 followed by a
     * {@code FAILED} poll, where the caller could have had a 404. {@code SimulationService} resolves
     * and checks the whole request, and writes the {@code QUEUED} run, before it reaches here.
     *
     * @param type       a short label for logs and the UI, e.g. {@code SIMULATION}
     * @param resourceId the row this job is producing — carried on every snapshot so a client knows
     *                   where the answer will be — or null
     * @param task       the computation
     * @throws JobQueueFullException if the bounded queue is full (rendered as 429)
     */
    public JobHandle submit(String type, Long resourceId, JobTask<?> task) {
        String jobId = UUID.randomUUID().toString();
        JobRecord record = new JobRecord(jobId, type, resourceId, properties.progressStep());
        jobs.put(jobId, record);
        try {
            Future<?> future = executor.submit(() -> execute(record, task));
            record.attach(future);
        } catch (RuntimeException rejection) {
            // Covers JobQueueFullException, which the rejection handler throws when the bounded
            // queue is full — it is a DomainException and so already a RuntimeException.
            //
            // The record must not outlive a submission that never happened: a client polling a
            // jobId whose task was never accepted would see QUEUED for ever.
            jobs.remove(jobId);
            throw rejection;
        }
        log.info("Queued {} job {} (resource {})", type, jobId, resourceId);
        return new JobHandle(jobId, type, resourceId);
    }

    /** One job's state, or empty if it never existed or has been evicted. */
    public Optional<JobSnapshot> status(String jobId) {
        JobRecord record = jobs.get(jobId);
        return record == null ? Optional.empty() : Optional.of(record.snapshot());
    }

    /**
     * The same, as the controller wants it.
     *
     * @throws EntityNotFoundException rendered as 404 — which is also the honest answer for a job
     *                                 that completed long enough ago to be evicted
     */
    public JobSnapshot require(String jobId) {
        return status(jobId).orElseThrow(() -> notFound(jobId));
    }

    private EntityNotFoundException notFound(String jobId) {
        return new EntityNotFoundException(
                ("No job with id %s. Jobs are held in memory and the most recent %d are retained, "
                        + "so a job that finished some time ago is gone even though its results are "
                        + "not — read them from the resource it produced.")
                        .formatted(jobId, properties.retention()));
    }

    /**
     * Requests cancellation.
     *
     * <p>Idempotent, and never an error: cancelling a job that has already finished is a no-op, and
     * telling the caller otherwise would only make the UI's stop button conditional on a race.
     *
     * @return true if this call moved the job towards cancellation; false if it was already terminal
     * @throws EntityNotFoundException if no such job is retained
     */
    public boolean cancel(String jobId) {
        JobRecord record = jobs.get(jobId);
        if (record == null) {
            throw notFound(jobId);
        }
        boolean acted = record.requestCancel();
        if (acted) {
            log.info("Cancellation requested for {} job {}", record.type, jobId);
        }
        return acted;
    }

    /** Every retained job, newest first — not a public endpoint; for tests and diagnostics. */
    public List<JobSnapshot> retained() {
        return jobs.values().stream()
                .map(JobRecord::snapshot)
                .sorted((a, b) -> b.submittedAt().compareTo(a.submittedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ the worker body

    private void execute(JobRecord record, JobTask<?> task) {
        MDC.put(MDC_KEY, record.jobId);
        try {
            if (!record.start()) {
                // Cancelled while it sat in the queue and the Future did not manage to drop it.
                return;
            }
            task.run(record);
            record.succeed();
            log.info("{} job {} finished", record.type, record.jobId);
        } catch (JobCancelledException cancelled) {
            record.markCancelled();
            log.info("{} job {} cancelled", record.type, record.jobId);
        } catch (Exception failure) {
            record.fail(failure);
            log.error("{} job {} failed", record.type, record.jobId, failure);
        } catch (Error fatal) {
            // An OutOfMemoryError from a run with too many replications is the realistic case here,
            // and a job stuck at RUNNING for ever afterwards would hide it.
            record.fail(fatal);
            throw fatal;
        } finally {
            evictOldFinished(record.jobId);
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Keeps at most {@link JobProperties#retention()} terminated jobs pollable.
     *
     * <p>Only terminated ones are ever evicted, so a queued or running job cannot vanish from under
     * a poller however many jobs are submitted behind it.
     */
    private void evictOldFinished(String jobId) {
        finished.addLast(jobId);
        while (finished.size() > properties.retention()) {
            String oldest = finished.pollFirst();
            if (oldest != null) {
                jobs.remove(oldest);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        // shutdownNow rather than shutdown: every task here is cancellable and idempotent to lose,
        // and a JVM held open by a two-hour Monte Carlo run is worse than a run that has to be
        // resubmitted. Queued tasks are simply dropped.
        executor.shutdownNow();
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "snrm-job-" + sequence.incrementAndGet());
            // Not a daemon: a job that is mid-write when the process is asked to stop should be
            // allowed to finish its statement. @PreDestroy interrupts them if that takes too long.
            thread.setDaemon(false);
            return thread;
        };
    }

    private RejectedExecutionHandler refuseWhenFull() {
        return (runnable, pool) -> {
            throw new JobQueueFullException(properties.workers(), properties.queueCapacity());
        };
    }

    // ---------------------------------------------------------------------- the record

    /**
     * One job's mutable state, and its own {@link ProgressSink}.
     *
     * <p>The two are the same object on purpose. A separate sink would have to hold a reference back
     * here anyway, and the progress fields are exactly the fields a report writes — splitting them
     * would buy nothing but a second class to keep in step.
     *
     * <p><strong>Reads are consistent, writes are cheap.</strong> {@link #report} writes two volatile
     * fields with no lock, because it is called from every replication thread and must not become the
     * contended thing in a Monte Carlo loop. {@link #snapshot()} synchronises, so a poller can never
     * see {@code DONE} beside a progress of 0.6.
     */
    private static final class JobRecord implements ProgressSink {

        private final String jobId;
        private final String type;
        private final Long resourceId;
        private final double progressStep;
        private final Instant submittedAt = Instant.now();

        private volatile double progress;
        private volatile String message = "Queued.";
        private volatile boolean cancelRequested;
        private volatile double lastPublished = -1;
        /** The task's last {@link ProgressSink#partial} payload; nulled on any terminal state. */
        private volatile Object partial;

        private JobStatus status = JobStatus.QUEUED;
        private Instant startedAt;
        private Instant finishedAt;
        private String error;
        private Future<?> future;

        private JobRecord(String jobId, String type, Long resourceId, double progressStep) {
            this.jobId = jobId;
            this.type = type;
            this.resourceId = resourceId;
            this.progressStep = progressStep;
        }

        synchronized void attach(Future<?> future) {
            this.future = future;
            if (cancelRequested) {
                // cancel() arrived between submit() and here; honour it now.
                future.cancel(false);
            }
        }

        /** @return false if the job was cancelled before a worker reached it */
        synchronized boolean start() {
            if (status != JobStatus.QUEUED) {
                return false;
            }
            if (cancelRequested) {
                status = JobStatus.CANCELLED;
                finishedAt = Instant.now();
                message = "Cancelled before it started.";
                return false;
            }
            status = JobStatus.RUNNING;
            startedAt = Instant.now();
            message = "Starting.";
            return true;
        }

        synchronized void succeed() {
            if (status.isTerminal()) {
                return;
            }
            status = JobStatus.DONE;
            finishedAt = Instant.now();
            progress = 1;
            message = "Complete.";
            // Superseded, never merged: the persisted result is now authoritative, and a stale
            // partial beside a DONE status would be two answers to one question (FR-17).
            partial = null;
        }

        synchronized void fail(Throwable failure) {
            if (status.isTerminal()) {
                return;
            }
            status = JobStatus.FAILED;
            finishedAt = Instant.now();
            // The message only. A stack trace is in the log; server.error.include-stacktrace=never
            // is the same policy stated for the synchronous paths.
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            message = "Failed.";
            // Figures from a computation that then failed describe nothing worth acting on.
            partial = null;
        }

        synchronized void markCancelled() {
            if (status.isTerminal()) {
                return;
            }
            status = JobStatus.CANCELLED;
            finishedAt = Instant.now();
            message = "Cancelled.";
            partial = null;
        }

        synchronized boolean requestCancel() {
            if (status.isTerminal()) {
                return false;
            }
            cancelRequested = true;
            if (future != null) {
                // false: never interrupt a running worker. On a queued task this removes it from the
                // queue and the worker body is never entered at all.
                future.cancel(false);
            }
            if (status == JobStatus.QUEUED) {
                status = JobStatus.CANCELLED;
                finishedAt = Instant.now();
                message = "Cancelled before it started.";
            }
            return true;
        }

        synchronized JobSnapshot snapshot() {
            // The RUNNING guard is belt on top of the terminal methods' braces: partial() is an
            // unsynchronised volatile write from replication threads, so a report racing a
            // cancellation could land after the null. A poller must never see figures beside a
            // terminal status, and this is the read where that is cheap to guarantee.
            return new JobSnapshot(jobId, type, status, progress, message, resourceId,
                    submittedAt, startedAt, finishedAt, error,
                    status == JobStatus.RUNNING ? partial : null);
        }

        @Override
        public void report(double fraction, String message) {
            double clamped = Math.min(1, Math.max(0, fraction));
            // Published in steps, so 200 replication completions do not become 200 writes for a bar
            // that moves in whole percent. A changed message always publishes: phases are few and
            // each one is what makes the poll readable.
            boolean newPhase = message != null && !message.equals(this.message);
            if (!newPhase && clamped < lastPublished + progressStep && clamped < 1) {
                return;
            }
            this.progress = clamped;
            this.lastPublished = clamped;
            if (message != null) {
                this.message = message;
            }
        }

        @Override
        public double progress() {
            return progress;
        }

        /**
         * One volatile write, unconditionally — unlike {@link #report}, which throttles.
         *
         * <p>Replacing a reference is as cheap as the throttle check would be, and throttling here
         * would trade away exactly what the payload is for: the freshest figures the poll can
         * carry. The payload is required immutable ({@link ProgressSink#partial}), so a poller
         * reading the old reference mid-write sees a consistent value either way.
         */
        @Override
        public void partial(Object partial) {
            this.partial = partial;
        }

        @Override
        public boolean cancelled() {
            return cancelRequested;
        }
    }
}
