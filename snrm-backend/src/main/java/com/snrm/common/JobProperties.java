package com.snrm.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the job framework is bounded.
 *
 * <p>Every value here exists to keep one researcher's machine usable while a Monte Carlo run is in
 * flight. The defaults assume the intended deployment — a single-user research tool on a
 * workstation — not a shared server.
 *
 * @param workers       how many jobs execute at once. Two, not the core count: a single simulation
 *                      job already saturates the machine through its own virtual-thread fan-out,
 *                      so a second worker exists to keep a short job from queueing behind a
 *                      long one, and a third would only make both slower
 * @param queueCapacity how many jobs may wait. Past this, {@code POST /simulations} answers 429
 *                      rather than accepting work it cannot honour — an unbounded queue turns a
 *                      submission storm into an out-of-memory failure an hour later, with no request
 *                      left to attribute it to
 * @param retention     how many finished jobs stay pollable. A completed job is only interesting
 *                      until the client has read its terminal status, and the run itself is durable
 *                      in {@code simulation_run} — so this is a courtesy window, not a record
 * @param progressStep  the smallest progress change worth publishing, as a fraction. A run of 200
 *                      replications reporting every completion would write the same volatile fields
 *                      200 times for a bar that moves in whole percent
 */
@ConfigurationProperties(prefix = "snrm.jobs")
public record JobProperties(
        Integer workers,
        Integer queueCapacity,
        Integer retention,
        Double progressStep) {

    public static final int DEFAULT_WORKERS = 2;
    public static final int DEFAULT_QUEUE_CAPACITY = 32;
    public static final int DEFAULT_RETENTION = 200;
    public static final double DEFAULT_PROGRESS_STEP = 0.01;

    /** Applies the defaults above to any value left unset, and refuses a nonsensical one. */
    public JobProperties {
        workers = positive(workers, DEFAULT_WORKERS, "snrm.jobs.workers");
        queueCapacity = positive(queueCapacity, DEFAULT_QUEUE_CAPACITY, "snrm.jobs.queue-capacity");
        retention = positive(retention, DEFAULT_RETENTION, "snrm.jobs.retention");
        if (progressStep == null || progressStep < 0) {
            progressStep = DEFAULT_PROGRESS_STEP;
        }
    }

    private static int positive(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, was " + value);
        }
        return value;
    }
}
