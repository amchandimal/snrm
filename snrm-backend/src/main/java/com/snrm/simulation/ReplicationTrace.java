package com.snrm.simulation;

import java.util.List;
import java.util.OptionalInt;

/**
 * One replication's whole horizon — the {@code SimulationTrace} that goes into a
 * {@code MetricContext}.
 *
 * <blockquote>"MetricContext carries the NetworkGraph snapshot and, for SIMULATED metrics, the
 * per-replication SimulationTrace list."</blockquote>
 *
 * <p>Immutable, and produced by a single virtual thread that then hands it over and touches nothing
 * else. It carries its own seed and index so a suspicious replication can be re-run on its
 * own: with the positional generator of {@link ReplicationRng}, {@code (seed, index)} reproduces
 * this trace exactly and independently of the other 199.
 *
 * @param index      0-based replication index. The paired baseline replication carries the same one,
 *                   which is what makes the differences behind {@code DISRUPTION_COST_DELTA} and
 *                   {@code LOSS_AREA} paired rather than a difference of independent means
 * @param seed       the run's base seed, recorded so this replication can be reproduced alone
 * @param baseline   whether this is a member of the undisrupted baseline set
 * @param onsetPeriod the first period a disruption event fired in, or empty if none did. Empty on
 *                   every baseline replication by construction, and on a disrupted one where every
 *                   event lost its probability draw — which is not the same as an onset of 0, and
 *                   is why this is an {@code OptionalInt} rather than a sentinel
 * @param periods    the horizon, in order
 */
public record ReplicationTrace(
        int index,
        long seed,
        boolean baseline,
        OptionalInt onsetPeriod,
        List<PeriodTrace> periods) {

    public ReplicationTrace {
        periods = periods == null ? List.of() : List.copyOf(periods);
    }

    /** Whether a disruption occurred at all in this replication. */
    public boolean wasDisrupted() {
        return onsetPeriod.isPresent();
    }

    /**
     * Served over total demand across the whole horizon — a <strong>ratio of sums</strong>.
     *
     * <p>Not the mean of the per-period fill rates, which is a different number: the ratio of sums
     * weights each period by its demand, the mean of ratios weights every period equally and lets a
     * quiet period with two units of demand count as much as a peak with two hundred. {@code FILL_RATE}
     * is served over total demand across the horizon, which is this one.
     */
    public double fillRate() {
        double served = 0;
        double demand = 0;
        for (PeriodTrace period : periods) {
            served += period.servedDemand();
            demand += period.totalDemand();
        }
        return demand <= 0 ? 1 : served / demand;
    }

    /** Sum of every period's five cost components — what {@code TOTAL_COST} reports. */
    public double totalCost() {
        double total = 0;
        for (PeriodTrace period : periods) {
            total += period.totalCost();
        }
        return total;
    }

    /** The period at {@code index}, for the paired comparisons the recovery metrics make. */
    public PeriodTrace period(int index) {
        return periods.get(index);
    }

    public int horizonPeriods() {
        return periods.size();
    }
}
