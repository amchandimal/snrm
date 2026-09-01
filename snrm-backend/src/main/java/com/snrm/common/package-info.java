/**
 * Shared value objects, errors, and the asynchronous job framework.
 *
 * <p>The time and rate units live here because every other module speaks them:
 * {@link com.snrm.common.TimeUnit}, the persisted {@link com.snrm.common.DurationAmount} and
 * {@link com.snrm.common.Rate}, the {@link com.snrm.common.RoundingPolicy} a network applies, and
 * {@link com.snrm.common.TimeBasis} — the value object carried on a {@code NetworkGraph} snapshot
 * that performs every conversion onto the simulation clock.
 *
 * <p>{@link com.snrm.common.DurationDto} and {@link com.snrm.common.RateDto} are their API form, the
 * {@code {value, unit}} objects, with {@link com.snrm.common.TimeValueMapper} reading and
 * {@link com.snrm.common.TimeValues} writing.
 *
 * <h2>The job framework</h2>
 *
 * <p>{@link com.snrm.common.JobService} runs long computations asynchronously — submit, poll,
 * cancel — and {@link com.snrm.common.JobController} exposes the poll and cancel endpoints. It
 * lives here rather than in {@code simulation} because it is deliberately ignorant of what it runs: a
 * job is a {@link com.snrm.common.JobTask}, a function from
 * {@link com.snrm.common.ProgressSink} to a result, and nothing in the framework imports a network, a
 * scenario or a run.
 *
 * <p>That ignorance is the point. The Phase 2 configuration search will run through this same
 * {@code JobService}, and its {@code ConfigurationStrategy} signature already names
 * {@code ProgressSink} as its last parameter — so the search will reach the framework through
 * exactly the seam the simulation engine does, and the reusability is a matter of one parameter type
 * rather than a shared base class.
 */
package com.snrm.common;
