package com.snrm.simulation;

/**
 * The independent sources of randomness in a replication.
 *
 * <blockquote>Stochastic elements — event occurrence (per-event probability), event timing/duration
 * jitter, demand noise, and random node/link failures via {@code failure_prob}. All draws come from
 * a seeded RNG per replication.</blockquote>
 *
 * <p>Four constants, one per element listed above, and they are <strong>separate streams rather
 * than one sequence</strong> for two reasons that both matter to the results:
 *
 * <ul>
 *   <li><strong>Common random numbers.</strong> The baseline replication set is paired
 *       with the disrupted one by index and shares {@link #DEMAND} and {@link #FAILURES}. So the
 *       paired difference behind {@code DISRUPTION_COST_DELTA} and {@code LOSS_AREA} isolates the
 *       disruption instead of measuring two independent samples of demand noise, and its confidence
 *       interval is dramatically tighter for the same replication count.</li>
 *   <li><strong>Draws cannot shift each other.</strong> With one sequence, adding a draw anywhere —
 *       a new stochastic element, an extra event, a network with one more link — renumbers every
 *       subsequent draw, and two runs of the "same" seed stop being comparable. With streams
 *       addressed by {@code (replication, stream, period)}, the numbers a given position yields are
 *       fixed for ever (see {@link ReplicationRng}).</li>
 * </ul>
 *
 * <p>The ordinal is part of the address, so <strong>constants must not be reordered or removed</strong>
 * — doing so changes every draw of every stored seed and silently invalidates every recorded run.
 * Append new ones at the end.
 */
public enum RngStream {

    /** Whether each event occurs at all in this replication, against its {@code probability}. */
    OCCURRENCE,

    /** Per-event perturbation of start period and window length. */
    TIMING,

    /** Multiplicative demand noise at customer nodes, per period. */
    DEMAND,

    /** Per-period independent node and link outages from {@code failure_prob}. */
    FAILURES
}
