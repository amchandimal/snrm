package com.snrm.simulation;

import com.snrm.scenario.PlannedEvent;
import com.snrm.scenario.RecoveryProfile;
import com.snrm.scenario.ScenarioPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.SplittableRandom;

/**
 * Step ① of the per-period loop: what fraction of each node's and each link's nominal capacity is
 * available this period.
 *
 * <blockquote>"Applies scenario events to derive each node's and link's available capacity
 * (availability multiplier × nominal capacity, following the event's severity and recovery
 * profile)."</blockquote>
 *
 * <p>One instance per replication. The per-replication draws — whether each event occurs at all, and
 * its timing jitter — are made once, in the constructor, from the {@link RngStream#OCCURRENCE} and
 * {@link RngStream#TIMING} streams; the per-period failure draws come from
 * {@link RngStream#FAILURES}, addressed by period so they cannot drift.
 *
 * <h2>Two multipliers, composed</h2>
 *
 * <p><strong>Events multiply.</strong> Where two events strike the same node — a regional event and
 * a node-specific one, or two overlapping windows — their availabilities are multiplied rather than
 * summed or minimised. Multiplication is the only composition consistent with reading
 * severity as "a capacity-availability multiplier reduction": two independent halvings leave a
 * quarter, and taking the minimum would let the second event be free while summing the reductions
 * could take availability below zero.
 *
 * <p><strong>A random failure is total.</strong> {@code failure_prob} is a per-period Bernoulli draw
 * ({@code GraphNode}'s "independent per-period failure probability"), and a hit sets
 * availability to 0 for that period: a failure is an outage, not a degradation, which is what
 * distinguishes it from a severity-graded event.
 *
 * <h2>An unconstrained element cannot be partly disrupted</h2>
 *
 * <p>A node or link with no declared capacity has an infinite ceiling, and {@code ∞ × 0.5} is still
 * infinite — halving a limit that does not bind changes nothing. So an availability strictly between
 * 0 and 1 leaves an unconstrained element alone, and only a total outage (availability 0) takes it
 * offline. That is stated here rather than left to arithmetic, because the alternative — quietly
 * treating a severity of 0.5 on an uncapped element as no disruption at all — is exactly the kind of
 * silent no-op a resilience study cannot afford. {@code FlowAllocator} applies the rule; this class
 * reports the multiplier.
 */
final class AvailabilityModel {

    private final SimulationNetwork network;
    private final List<ActiveEvent> active;
    private final boolean applyFailures;
    private final ReplicationRng rng;
    private final int onsetPeriod;

    private AvailabilityModel(SimulationNetwork network, List<ActiveEvent> active,
            boolean applyFailures, ReplicationRng rng) {
        this.network = network;
        this.active = active;
        this.applyFailures = applyFailures;
        this.rng = rng;
        this.onsetPeriod = active.stream()
                .mapToInt(event -> (int) event.start)
                .min()
                .orElse(-1);
    }

    /**
     * Draws this replication's events and builds the model.
     *
     * @param plan          the scenario, already resolved against this network. The undisrupted
     *                      baseline set passes {@link ScenarioPlan#withoutEvents()}
     * @param applyFailures whether {@code failure_prob} outages apply. False for the baseline set
     *                      only when {@link SimulationParams#baselineSuppressesFailures()} is set —
     *                      see that parameter for why the default leaves them in both sets
     */
    static AvailabilityModel forReplication(SimulationNetwork network, ScenarioPlan plan,
            SimulationParams params, ReplicationRng rng, boolean applyFailures) {
        List<ActiveEvent> active = new ArrayList<>();
        SplittableRandom occurrence = rng.stream(RngStream.OCCURRENCE);
        SplittableRandom timing = rng.stream(RngStream.TIMING);

        for (PlannedEvent event : plan.effectiveEvents()) {
            // The draw is taken whether or not the event is certain, so that adding an uncertain
            // event to a scenario does not renumber the draws of the certain ones beside it. The
            // stream is per-replication and cheap; a skipped draw would cost reproducibility.
            double roll = occurrence.nextDouble();
            long startJitter = ReplicationRng.jitter(timing, params.timingJitterPeriods());
            long windowJitter = ReplicationRng.jitter(timing, params.timingJitterPeriods());
            if (!event.isCertain() && roll >= event.probability()) {
                continue;
            }
            long start = Math.max(0, event.startPeriod() + startJitter);
            long window = Math.max(1, event.windowPeriods() + windowJitter);
            active.add(ActiveEvent.of(network, event, start, window));
        }
        return new AvailabilityModel(network, List.copyOf(active), applyFailures, rng);
    }

    /**
     * Fills the two availability arrays for one period.
     *
     * <p>Written into caller-owned arrays rather than returning fresh ones: this runs once per
     * period per replication, and allocating two arrays of the network's size each time is the one
     * allocation in the loop that would show up on a 1,000-node network × 200 replications × a
     * 52-period horizon (FR-04).
     *
     * @param nodeAvailability filled with a multiplier in {@code [0,1]} per node index
     * @param linkAvailability filled with a multiplier in {@code [0,1]} per link index
     * @return true if any event was in flight this period — what the trace records as "disrupted"
     */
    boolean apply(int period, double[] nodeAvailability, double[] linkAvailability) {
        java.util.Arrays.fill(nodeAvailability, 1.0);
        java.util.Arrays.fill(linkAvailability, 1.0);

        boolean disrupted = false;
        for (ActiveEvent event : active) {
            long elapsed = period - event.start;
            if (elapsed < 0 || elapsed >= event.window) {
                continue;
            }
            disrupted = true;
            double multiplier = event.profile.availabilityAt(elapsed, event.window, event.severity);
            for (int nodeIndex : event.nodeIndices) {
                nodeAvailability[nodeIndex] *= multiplier;
            }
            for (int linkIndex : event.linkIndices) {
                linkAvailability[linkIndex] *= multiplier;
            }
        }

        if (applyFailures) {
            // One generator for the whole period, consumed in a fixed order — nodes then links, both
            // in snapshot order — so a paired baseline replication drawing from the same address
            // gets exactly the same outages (common random numbers).
            SplittableRandom failures = rng.stream(RngStream.FAILURES, period);
            for (int i = 0; i < network.nodeCount(); i++) {
                if (failures.nextDouble() < network.nodeFailureProb(i)) {
                    nodeAvailability[i] = 0;
                }
            }
            for (int e = 0; e < network.linkCount(); e++) {
                if (failures.nextDouble() < network.linkFailureProb(e)) {
                    linkAvailability[e] = 0;
                }
            }
        }
        return disrupted;
    }

    /**
     * The first period any event fires in this replication, or empty if none does.
     *
     * <p>The onset every recovery metric is measured from: {@code TTR} counts periods
     * after it, {@code RESILIENCE_INDEX} averages performance from it. A replication in which no
     * event occurred — because every one of them lost its probability draw — has no onset and
     * contributes no observation to either, which is different from contributing a zero.
     */
    OptionalInt onsetPeriod() {
        return onsetPeriod < 0 ? OptionalInt.empty() : OptionalInt.of(onsetPeriod);
    }

    /** Whether any event occurred in this replication at all. */
    boolean hasEvents() {
        return !active.isEmpty();
    }

    /**
     * One event as this replication will apply it: occurred, jittered, and mapped onto array
     * indices.
     *
     * <p>The id-to-index mapping happens once here rather than once per period. A {@code REGION}
     * event over forty nodes would otherwise cost forty hash lookups in every period of every
     * replication, which is a measurable fraction of a loop whose real work is one flow solve.
     */
    private record ActiveEvent(long start, long window, double severity, RecoveryProfile profile,
            int[] nodeIndices, int[] linkIndices) {

        static ActiveEvent of(SimulationNetwork network, PlannedEvent event, long start,
                long window) {
            return new ActiveEvent(start, window, event.severity(), event.recoveryProfile(),
                    indices(event.nodeIds(), network::indexOfNode),
                    indices(event.linkIds(), network::indexOfLink));
        }

        private static int[] indices(java.util.Set<Long> ids,
                java.util.function.Function<Long, Integer> lookup) {
            return ids.stream()
                    .map(lookup)
                    .filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .toArray();
        }
    }
}
