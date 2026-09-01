package com.snrm.simulation;

import java.util.List;

/**
 * Everything one simulation run produced: the disrupted replications, and the undisrupted baseline
 * set they are measured against.
 *
 * <blockquote>"Every simulation automatically includes one undisrupted baseline replication set,
 * since {@code TTR}, {@code LOSS_AREA}, {@code RESILIENCE_INDEX} and
 * {@code DISRUPTION_COST_DELTA} are all defined relative to baseline performance."</blockquote>
 *
 * <p><strong>The two lists are the same length and are paired by index.</strong> Disrupted
 * replication <em>i</em> and baseline replication <em>i</em> share a demand realisation and — unless
 * {@link SimulationParams#baselineSuppressesFailures()} says otherwise — the same random outages,
 * because {@link ReplicationRng} addresses those streams by {@code (index, stream, period)} and both
 * pass the same index. So {@link #paired} gives a metric two horizons that differ in the disruption
 * and in nothing else, and a difference taken between them is a paired difference: its confidence
 * interval reflects the disruption's effect rather than the variance of two independent demand
 * samples. That is worth several-fold in replication count.
 *
 * <p><strong>The baseline list is empty on a run that disrupted nothing</strong> — the baseline run
 * of FR-17, where {@code MonteCarloRunner} skips the pairing because the undisrupted set of an
 * undisrupted run would be the same replications computed twice. The four metrics defined against a
 * disruption ({@code TTR}, {@code LOSS_AREA}, {@code DISRUPTION_COST_DELTA},
 * {@code RESILIENCE_INDEX}) each guard on that emptiness and produce no rows, which is a valid result;
 * {@link #paired} must not be called when it holds.
 *
 * <p><strong>{@link #elements} is the one part that is not per-replication.</strong> The per-element
 * series arrives already averaged, because it is folded and discarded as the replications
 * finish rather than retained (see {@link ElementAccumulator}). It is null on a run that asked not to
 * record it, which is a different statement from an empty series: nothing was recorded, rather than
 * a network that did nothing.
 *
 * @param disrupted the replications with the scenario applied
 * @param baseline  the undisrupted set, index-paired with {@code disrupted}; empty when the run
 *                  disrupted nothing and the pairing was skipped (FR-17)
 * @param horizonPeriods how many periods each replication covers
 * @param elements  the per-element series, already averaged across replications; null
 *                  when the run did not record one
 */
public record SimulationTraces(
        List<ReplicationTrace> disrupted,
        List<ReplicationTrace> baseline,
        int horizonPeriods,
        ElementSeries elements) {

    /**
     * A run with no per-element series — the shape every caller used before the series existed, and
     * what a test that is only interested in the metric suite still wants.
     */
    public SimulationTraces(List<ReplicationTrace> disrupted, List<ReplicationTrace> baseline,
            int horizonPeriods) {
        this(disrupted, baseline, horizonPeriods, null);
    }

    public SimulationTraces {
        disrupted = disrupted == null ? List.of() : List.copyOf(disrupted);
        baseline = baseline == null ? List.of() : List.copyOf(baseline);
        if (!disrupted.isEmpty() && !baseline.isEmpty() && disrupted.size() != baseline.size()) {
            throw new IllegalArgumentException(("The baseline set must be index-paired with the "
                    + "disrupted one, but there are %d disrupted and %d baseline "
                    + "replications.").formatted(disrupted.size(), baseline.size()));
        }
    }

    /** How many replications of each kind — the requested N, not the {@code 2N} actually executed. */
    public int replications() {
        return disrupted.size();
    }

    /**
     * Disrupted replication {@code i} beside the baseline replication it is paired with.
     *
     * @param index 0-based
     */
    public Pair paired(int index) {
        return new Pair(disrupted.get(index), baseline.get(index));
    }

    /** Whether anything was actually disrupted — see {@code ScenarioPlan.isUndisrupted()}. */
    public boolean anyDisruption() {
        return disrupted.stream().anyMatch(ReplicationTrace::wasDisrupted);
    }

    /** One disrupted replication and its undisrupted twin. */
    public record Pair(ReplicationTrace disrupted, ReplicationTrace baseline) {

        /** The period the disruption began, or empty if this replication had none. */
        public java.util.OptionalInt onset() {
            return disrupted.onsetPeriod();
        }
    }
}
