package com.snrm.simulation;

import java.util.SplittableRandom;

/**
 * The seeded randomness of one replication, addressed by position rather than drawn in sequence.
 *
 * <blockquote>"All draws come from a seeded RNG per replication. … The run stores seed + parameters
 * for exact reproducibility."</blockquote>
 *
 * <h2>Position, not sequence</h2>
 *
 * <p>The obvious implementation is one {@code Random} per replication, drawn from in whatever order
 * the code happens to ask. It reproduces — until the code changes. Add a stochastic element, give a
 * network one more link, reorder two loops, and every draw after that point shifts; a run replayed
 * from its stored seed then produces different numbers, and the reproducibility the engine promises
 * turns out to have been a promise about one build of the software.
 *
 * <p>So a draw here is addressed rather than consumed. The generator for
 * {@code (replication, stream, period)} is derived from the base seed by hashing those four values
 * together, which means:
 *
 * <ul>
 *   <li>replication 37's demand noise in period 12 is the same number whatever else the engine does,
 *       in whatever order, on whatever thread;</li>
 *   <li>the baseline replication can share exactly the demand and failure draws of the
 *       disrupted replication it is paired with — the common random numbers that make
 *       {@code DISRUPTION_COST_DELTA} a paired difference rather than a difference of two
 *       independent means — simply by asking for the same address;</li>
 *   <li>a replication needs no synchronisation and no ordering guarantee, which is what lets the
 *       hundreds of virtual threads run in any order at all.</li>
 * </ul>
 *
 * <p>The cost is one generator construction per stream per period — a handful of arithmetic
 * operations, against a min-cost-flow solve in the same period. It is not measurable.
 *
 * <h2>The mixing function</h2>
 *
 * <p>SplitMix64's finaliser (Steele, Lea &amp; Flood, 2014), which is the avalanche step inside
 * {@link SplittableRandom} itself. Two addresses differing in one bit produce uncorrelated
 * generators, which is the property this depends on: without it, replications 1 and 2 would be
 * near-neighbours in the sequence and their traces would correlate.
 */
public final class ReplicationRng {

    private final long baseSeed;
    private final int replicationIndex;

    /**
     * @param baseSeed         the run's recorded seed ({@code simulation_run.params_json})
     * @param replicationIndex 0-based index of this replication. The baseline replication paired
     *                         with disrupted replication <em>i</em> passes the same index, which is
     *                         what makes their shared streams identical
     */
    public ReplicationRng(long baseSeed, int replicationIndex) {
        this.baseSeed = baseSeed;
        this.replicationIndex = replicationIndex;
    }

    /**
     * The generator for one stream in one period.
     *
     * @param stream which source of randomness
     * @param period the period it belongs to; use {@link #ONCE_PER_REPLICATION} for draws made once
     *               for the whole replication, such as whether an event occurs
     */
    public SplittableRandom stream(RngStream stream, int period) {
        return new SplittableRandom(address(baseSeed, replicationIndex, stream.ordinal(), period));
    }

    /** The period argument for a draw made once per replication rather than once per period. */
    public static final int ONCE_PER_REPLICATION = -1;

    /** Draws made once for the whole replication — event occurrence and timing jitter. */
    public SplittableRandom stream(RngStream stream) {
        return stream(stream, ONCE_PER_REPLICATION);
    }

    /** The run's seed, for the trace so a single replication can be reproduced on its own. */
    public long baseSeed() {
        return baseSeed;
    }

    public int replicationIndex() {
        return replicationIndex;
    }

    /**
     * Combines the four coordinates into a seed. Each is mixed as it is folded in, so a change in
     * any one of them changes every bit of the result with probability one half.
     *
     * <p>The odd multipliers are the golden-ratio-derived constants SplitMix64 uses; they are
     * arbitrary but must be <em>fixed</em>, because changing one invalidates every stored seed.
     */
    private static long address(long seed, int replication, int stream, int period) {
        long mixed = mix(seed);
        mixed = mix(mixed + 0x9E3779B97F4A7C15L * (replication + 1L));
        mixed = mix(mixed + 0xBF58476D1CE4E5B9L * (stream + 1L));
        mixed = mix(mixed + 0x94D049BB133111EBL * (period + 2L));
        return mixed;
    }

    /** SplitMix64's finaliser. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * A multiplicative demand-noise factor with mean 1 and the given coefficient of variation,
     * truncated at zero.
     *
     * <p>Normal rather than lognormal: the parameter is plain demand noise, the coefficient of
     * variation a researcher enters is a standard deviation over a mean, and a normal factor makes
     * that exactly what it appears to be. Truncation is what keeps demand non-negative — a negative
     * realisation would be a customer supplying the network — and it does bias the mean upward very
     * slightly at large cv, which is stated here rather than hidden: at cv = 0.3 the truncated mean
     * is 1.0004, and a cv large enough to matter is a sign the demand model, not the truncation,
     * wants revisiting.
     *
     * @param cv coefficient of variation; 0 returns exactly 1 with no draw taken
     */
    public static double demandFactor(SplittableRandom random, double cv) {
        if (cv <= 0) {
            return 1;
        }
        return Math.max(0, 1 + cv * random.nextGaussian());
    }

    /**
     * A whole-period jitter drawn uniformly from {@code [−magnitude, +magnitude]}.
     *
     * <p>Uniform and symmetric, so jitter shifts an event's timing without shortening or lengthening
     * it on average — an asymmetric draw would make "add timing uncertainty" quietly also mean "make
     * disruptions worse", and the two are different modelling statements.
     */
    public static long jitter(SplittableRandom random, int magnitude) {
        if (magnitude <= 0) {
            return 0;
        }
        return random.nextInt(-magnitude, magnitude + 1);
    }
}
