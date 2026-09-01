package com.snrm.common;

/**
 * The whole of what a long computation sees of the job that is running it.
 *
 * <p>Two questions and one offering, and deliberately no more: <em>here is how far I have got</em>
 * ({@link #report}), <em>should I stop?</em> ({@link #cancelled()}), and <em>here is what I know so
 * far</em> ({@link #partial}). A task that could reach {@link JobService} could cancel its
 * neighbours, read another job's progress, or submit work from inside the pool it is running in —
 * none of which any computation needs and all of which are ways to deadlock a bounded executor.
 *
 * <p><strong>This is the type the Phase 2 strategy needs.</strong> That interface is
 *
 * <blockquote><pre>
 * SearchResult search(NetworkGraph base, ScenarioSet scenarios, LeverBounds bounds,
 *                     Objectives objectives, SearchBudget budget, ProgressSink progress);
 * </pre></blockquote>
 *
 * <p>so the configuration engine reports through exactly this interface and reaches the job
 * framework through exactly the same seam the simulation engine does. That is the reusability
 * wanted here, expressed as one parameter rather than as a shared base class.
 *
 * <p><strong>Cancellation is cooperative</strong>. Nothing here interrupts a
 * thread: a computation is expected to ask {@link #cancelled()} at a granularity of its own choosing
 * — the simulation engine asks once per period and once per replication — and to stop by throwing
 * {@link JobCancelledException}. Killing a worker mid-write is how a run ends up with half its
 * metric rows persisted and a {@code DONE} status.
 *
 * <p>Implementations are called from many threads at once: the Monte Carlo runner reports
 * from whichever virtual thread finished a replication. {@link JobService}'s implementation writes
 * volatile fields, so a report is cheap and a reader never sees a torn value.
 */
public interface ProgressSink {

    /**
     * Records how far the computation has got, and what it is doing.
     *
     * @param fraction completed work in {@code [0,1]}; values outside are clamped rather than
     *                 rejected, because a progress report is not worth failing a job over
     * @param message  a short human-readable phase, e.g. {@code "replication 137/200"}; null leaves
     *                 the previous message in place
     */
    void report(double fraction, String message);

    /** Leaves the fraction alone and only changes the message — for a phase with no countable work. */
    default void message(String message) {
        report(progress(), message);
    }

    /**
     * Publishes what the computation knows so far — the provisional figures (FR-17).
     *
     * <p>The payload is <strong>opaque to the framework</strong>, exactly as the task itself is: the
     * simulation engine publishes its streaming replication statistics
     * ({@code simulation/ProvisionalFigures}), and the Phase 2 search — whose candidates
     * are equally slow and equally worth watching — will publish a shape of its own, through this
     * same method, with nothing in {@code common} to change. It must be a small immutable value:
     * the poll returns it verbatim, and each call <em>replaces</em> the last.
     *
     * <p><strong>Superseded, never merged.</strong> The figures live only while the job runs; the
     * framework discards them the moment the job reaches a terminal state, because from then the
     * persisted result is authoritative and a stale partial beside a {@code DONE} status would be
     * two answers to one question. A client must label them provisional and must not persist or
     * export them.
     *
     * <p>The default discards the payload, so an engine can publish unconditionally and a test
     * running it outside a job pays nothing.
     */
    default void partial(Object partial) {
        // discarded unless the sink chooses to carry it
    }

    /** The fraction last reported, so {@link #message} can leave it untouched. */
    double progress();

    /**
     * Whether cancellation has been requested.
     *
     * <p>A task that observes {@code true} should stop at its next safe point and throw
     * {@link JobCancelledException}. Ignoring it is not an error — a computation short enough not to
     * bother is entitled to run to completion — but a long one that never asks cannot be cancelled
     * at all, which is the failure mode this method exists to prevent.
     */
    boolean cancelled();

    /**
     * Throws if cancellation has been requested; the one-line form of the check above.
     *
     * @throws JobCancelledException if {@link #cancelled()} holds
     */
    default void checkCancelled() {
        if (cancelled()) {
            throw new JobCancelledException();
        }
    }

    /**
     * A sink that records nothing and is never cancelled.
     *
     * <p>For calling an engine outside a job: a unit test, or the Phase 2 search scoring a candidate
     * synchronously. It exists so no engine needs a null check on its progress parameter.
     */
    static ProgressSink none() {
        return NoOpProgressSink.INSTANCE;
    }
}

/**
 * The {@link ProgressSink#none()} singleton. Package-private and in this file deliberately: it has
 * no independent meaning and putting it beside the interface keeps the contract in one place.
 */
final class NoOpProgressSink implements ProgressSink {

    static final NoOpProgressSink INSTANCE = new NoOpProgressSink();

    private NoOpProgressSink() {
    }

    @Override
    public void report(double fraction, String message) {
        // discarded
    }

    @Override
    public double progress() {
        return 0;
    }

    @Override
    public boolean cancelled() {
        return false;
    }
}
