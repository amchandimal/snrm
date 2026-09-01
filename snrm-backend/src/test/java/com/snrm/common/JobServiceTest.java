package com.snrm.common;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * The job framework — submit, poll, cancel — with no Spring context and no database.
 *
 * <p>Every test here is about the framework's promises rather than about simulations, which is the
 * property that makes it reusable by the Phase 2 search: nothing below mentions a
 * network, a scenario or a run.
 */
@DisplayName("JobService")
class JobServiceTest {

    private static JobService serviceWith(int workers, int queueCapacity) {
        return new JobService(new JobProperties(workers, queueCapacity, 200, 0.0));
    }

    @Test
    @DisplayName("a submitted job runs, reports progress and reaches DONE")
    void happyPath() {
        JobService jobs = serviceWith(1, 4);
        JobHandle handle = jobs.submit("TEST", 42L, progress -> {
            progress.report(0.5, "halfway");
            return "done";
        });

        assertThat(handle.resourceId()).isEqualTo(42L);
        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.require(handle.jobId()).status()).isEqualTo(JobStatus.DONE));

        JobSnapshot snapshot = jobs.require(handle.jobId());
        assertThat(snapshot.progress()).isEqualTo(1.0);
        assertThat(snapshot.error()).isNull();
        assertThat(snapshot.startedAt()).isNotNull();
        assertThat(snapshot.finishedAt()).isNotNull();
        assertThat(snapshot.resourceId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("a thrown exception becomes FAILED with its message, not CANCELLED")
    void failure() {
        JobService jobs = serviceWith(1, 4);
        JobHandle handle = jobs.submit("TEST", null, progress -> {
            throw new IllegalStateException("the flow was infeasible");
        });

        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.require(handle.jobId()).status()).isEqualTo(JobStatus.FAILED));
        assertThat(jobs.require(handle.jobId()).error()).isEqualTo("the flow was infeasible");
    }

    @Test
    @DisplayName("cancellation is cooperative: the task is asked and stops, and lands in CANCELLED")
    void cooperativeCancellation() throws Exception {
        JobService jobs = serviceWith(1, 4);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sawCancellation = new AtomicBoolean();

        JobHandle handle = jobs.submit("TEST", null, progress -> {
            started.countDown();
            while (true) {
                if (progress.cancelled()) {
                    sawCancellation.set(true);
                    throw new JobCancelledException();
                }
                Thread.sleep(5);
            }
        });

        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(jobs.cancel(handle.jobId())).isTrue();

        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.require(handle.jobId()).status()).isEqualTo(JobStatus.CANCELLED));
        // Never interrupted: the task observed the flag and chose to stop, which is what keeps a
        // half-written result out of the database.
        assertThat(sawCancellation).isTrue();
        assertThat(jobs.require(handle.jobId()).error()).isNull();
    }

    @Test
    @DisplayName("a job cancelled before it starts never runs at all")
    void cancelledWhileQueued() throws Exception {
        JobService jobs = serviceWith(1, 8);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        AtomicInteger secondJobRuns = new AtomicInteger();

        jobs.submit("BLOCKER", null, progress -> {
            blockerStarted.countDown();
            release.await();
            return null;
        });
        assertThat(blockerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        JobHandle queued = jobs.submit("TEST", null, progress -> {
            secondJobRuns.incrementAndGet();
            return null;
        });
        assertThat(jobs.require(queued.jobId()).status()).isEqualTo(JobStatus.QUEUED);

        assertThat(jobs.cancel(queued.jobId())).isTrue();
        assertThat(jobs.require(queued.jobId()).status()).isEqualTo(JobStatus.CANCELLED);

        release.countDown();
        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.retained()).allSatisfy(
                        job -> assertThat(job.status().isTerminal()).isTrue()));
        assertThat(secondJobRuns).hasValue(0);
    }

    @Test
    @DisplayName("cancelling a finished job is a no-op, not an error")
    void cancellationIsIdempotent() {
        JobService jobs = serviceWith(1, 4);
        JobHandle handle = jobs.submit("TEST", null, progress -> null);
        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.require(handle.jobId()).status().isTerminal()).isTrue());

        assertThat(jobs.cancel(handle.jobId())).isFalse();
        assertThat(jobs.require(handle.jobId()).status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    @DisplayName("a full queue is refused with JOB_QUEUE_FULL rather than accepted and forgotten")
    void boundedQueue() throws Exception {
        JobService jobs = serviceWith(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);

        jobs.submit("BLOCKER", null, progress -> {
            running.countDown();
            release.await();
            return null;
        });
        assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        jobs.submit("QUEUED", null, progress -> null);          // fills the single queue slot

        assertThatThrownBy(() -> jobs.submit("REFUSED", null, progress -> null))
                .isInstanceOf(JobQueueFullException.class)
                .satisfies(thrown -> assertThat(((JobQueueFullException) thrown).code())
                        .isEqualTo("JOB_QUEUE_FULL"));

        release.countDown();
    }

    @Test
    @DisplayName("a refused submission leaves no job behind for a poller to find")
    void refusedSubmissionLeavesNoRecord() throws Exception {
        JobService jobs = serviceWith(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);

        jobs.submit("BLOCKER", null, progress -> {
            running.countDown();
            release.await();
            return null;
        });
        assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        jobs.submit("QUEUED", null, progress -> null);

        int before = jobs.retained().size();
        assertThatThrownBy(() -> jobs.submit("REFUSED", null, progress -> null))
                .isInstanceOf(JobQueueFullException.class);
        assertThat(jobs.retained()).hasSize(before);

        release.countDown();
    }

    @Test
    @DisplayName("an unknown job is a 404, and so is one evicted from the retention window")
    void unknownJob() {
        JobService jobs = serviceWith(1, 4);
        assertThat(jobs.status("no-such-job")).isEmpty();
        assertThatThrownBy(() -> jobs.require("no-such-job"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("read them from the resource it produced");
    }

    @Test
    @DisplayName("progress is clamped into [0,1] rather than rejected")
    void progressIsClamped() {
        JobService jobs = serviceWith(1, 4);
        JobHandle handle = jobs.submit("TEST", null, progress -> {
            progress.report(-4, "nonsense");
            assertThat(progress.progress()).isEqualTo(0);
            progress.report(17, "also nonsense");
            assertThat(progress.progress()).isEqualTo(1);
            return null;
        });
        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.require(handle.jobId()).status()).isEqualTo(JobStatus.DONE));
    }

    @Test
    @DisplayName("a published partial is on the poll while the job runs, and discarded once it "
            + "is terminal — superseded, never merged (FR-17)")
    void partialIsCarriedThenDiscarded() throws Exception {
        JobService jobs = serviceWith(1, 4);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        JobHandle handle = jobs.submit("TEST", null, progress -> {
            progress.partial("what I know so far");
            published.countDown();
            release.await();
            return null;
        });

        assertThat(published.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.require(handle.jobId()).partial())
                        .isEqualTo("what I know so far"));

        release.countDown();
        await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.require(handle.jobId()).status()).isEqualTo(JobStatus.DONE));
        // The persisted result is now the authority; figures beside a terminal status would be two
        // answers to one question.
        assertThat(jobs.require(handle.jobId()).partial()).isNull();
    }

    @Test
    @DisplayName("ProgressSink.none() records nothing and is never cancelled")
    void noOpSink() {
        ProgressSink sink = ProgressSink.none();
        sink.report(0.5, "ignored");
        assertThat(sink.progress()).isEqualTo(0);
        assertThat(sink.cancelled()).isFalse();
        sink.checkCancelled();
    }
}
