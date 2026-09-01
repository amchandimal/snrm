package com.snrm.simulation;

import com.snrm.common.ProgressSink;
import com.snrm.scenario.ScenarioPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The per-period loop, for one replication.
 *
 * <blockquote>
 * The engine is a discrete-time, period-based simulator over a finite horizon. … Per period it:
 * <ol>
 *   <li>Applies scenario events to derive each node's and link's available capacity …</li>
 *   <li>Realises demand at customer nodes (deterministic or sampled).</li>
 *   <li>Allocates flow from supply to demand through the network.</li>
 *   <li>Updates inventories.</li>
 *   <li>Records the period trace (served/unserved demand, costs, inventory levels).</li>
 * </ol>
 * </blockquote>
 *
 * <p>One instance per run, and it holds no state: {@link #run} is called from every virtual thread
 * of the Monte Carlo fan-out at once and keeps all of a replication's state in local arrays. The
 * {@link SimulationNetwork} and {@link ScenarioPlan} it reads are immutable and shared, which is what
 * makes that safe without a lock.
 *
 * <h2>The inventory identity</h2>
 *
 * <p>Everything the loop does to stock reduces to one line, and it is asserted rather than assumed:
 *
 * <pre>
 *   onHand'(i) = onHand(i)                    stock at the start of the period
 *              + arrivals(i)                  what the pipeline delivered this period
 *              − stockDrawn(i)                what the allocation dispatched
 *              + heldNow(i)                   what arrived and stayed (no processing dwell)
 * </pre>
 *
 * <p>with two quantities deliberately <em>not</em> in it: material entering a node's processing
 * dwell, and material dispatched onto a link with a lead time. Both leave the source now and land at
 * the target later — this is pipeline inventory — so they are added to a future period's arrivals
 * instead. The pipeline is a small circular buffer, one slot per period of the longest delay in the
 * network, which is what keeps a 200-replication run's memory in kilobytes rather than in the
 * horizon × node product.
 *
 * <h2>The per-element trace (FR-18)</h2>
 *
 * <p>When a run asks for it, the loop also fills an {@link ElementTrace} — every node's and every
 * link's own history, which is what a playback view draws and what {@link PeriodTrace} aggregates
 * away. It is written into rather than derived afterwards, because four of its quantities exist only
 * <em>inside</em> a period and are gone by the time it ends: arrivals are read out of the pipeline
 * slot in step ① and the slot is cleared on the same statement; availability is overwritten in step
 * ② of the next period; and the flow solution of step ④ is a local. The capture points are marked in
 * the loop below.
 *
 * <p><strong>Recording changes no simulated number.</strong> Nothing here reads the trace back, and
 * a run with recording switched off takes exactly the same decisions — which is why
 * {@code SimulationParams.ENGINE_VERSION} does not move for it.
 *
 * <h2>Warm-up is real, and is why the baseline exists</h2>
 *
 * <p>A network whose links carry lead times cannot serve anything in its first periods: material has
 * to physically travel down it first. Fill rate therefore ramps up from whatever the opening
 * inventory supports, and a raw {@code FILL_RATE} over the whole horizon is depressed by that ramp.
 * This is not an artefact to correct — it is what the network does — and it is precisely why the
 * recovery metrics are all defined against the undisrupted baseline set, which
 * has the identical ramp. {@code LOSS_AREA}, {@code TTR} and {@code RESILIENCE_INDEX} see the
 * difference the disruption made and nothing else.
 */
final class SimulationEngine {

    private final SimulationNetwork network;
    private final SimulationParams params;
    private final Quantiser quantiser;
    private final FlowAllocator allocator;

    SimulationEngine(SimulationNetwork network, SimulationParams params, Quantiser quantiser) {
        this.network = network;
        this.params = params;
        this.quantiser = quantiser;
        this.allocator = new FlowAllocator(network, params, quantiser);
    }

    /**
     * One replication's two products: the per-period trace every metric is computed from, and the
     * per-element trace a playback view is drawn from (FR-18).
     *
     * <p>Two objects rather than one because they have opposite lifetimes. {@link #trace()} is
     * retained for the whole run — {@link SimulationTraces} hands the metric suite every
     * replication's — while {@link #elements()} is folded into {@link ElementAccumulator} and dropped
     * immediately. Putting the element arrays on {@code ReplicationTrace} would make every
     * replication's element history live as long as the run, which is the one thing the fold exists
     * to prevent.
     *
     * @param elements null when the run did not ask for element recording
     */
    record ReplicationOutcome(ReplicationTrace trace, ElementTrace elements) {
    }

    /**
     * Runs one replication over the whole horizon.
     *
     * @param plan             the scenario. The baseline set passes
     *                         {@link ScenarioPlan#withoutEvents()}
     * @param replicationIndex 0-based; the paired baseline replication passes the same index, which
     *                         is what makes their demand and failure draws identical
     * @param baseline         whether this is a baseline replication, for the trace
     * @param progress         asked between periods, so a cancelled run stops within one period
     *                         rather than at the end of the horizon
     * @param elements         the per-element trace to fill, or null to record none.
     *                         Supplied by the caller rather than allocated here so the caller can
     *                         fold and drop it the moment this returns
     */
    ReplicationOutcome run(ScenarioPlan plan, int replicationIndex, boolean baseline,
            ProgressSink progress, ElementTrace elements) {

        int nodeCount = network.nodeCount();
        int horizon = params.horizonPeriods();
        int depth = network.pipelineDepth();

        ReplicationRng rng = new ReplicationRng(params.seed(), replicationIndex);
        boolean applyFailures = params.includeRandomFailures()
                && !(baseline && params.baselineSuppressesFailures());
        AvailabilityModel availability =
                AvailabilityModel.forReplication(network, plan, params, rng, applyFailures);

        double[] onHand = new double[nodeCount];
        double[] inTransit = new double[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            onHand[i] = network.initialInventory(i);
        }
        // Circular by period: slot (t mod depth) holds what arrives in period t. Depth is one past
        // the longest delay in the network, so no shipment can overtake the slot it is waiting in.
        double[][] pipeline = new double[depth][nodeCount];

        double[] nodeAvailability = new double[nodeCount];
        double[] linkAvailability = new double[network.linkCount()];
        double[] demand = new double[nodeCount];
        // Scratch for the inbound-lead mean of step ⑤. Allocated once per replication rather than
        // once per period, for the reason AvailabilityModel states about its two arrays.
        double[] leadWeighted = elements == null ? null : new double[nodeCount];
        double[] dispatchedToward = elements == null ? null : new double[nodeCount];

        List<PeriodTrace> periods = new ArrayList<>(horizon);

        for (int t = 0; t < horizon; t++) {
            progress.checkCancelled();

            // ① Arrivals — the pipeline delivers, and the slot is cleared for reuse.
            double[] arriving = pipeline[t % depth];
            if (elements != null) {
                // Captured BEFORE the slot is zeroed below: after the loop this array is all zeros
                // and what the pipeline delivered is unrecoverable.
                System.arraycopy(arriving, 0, elements.arrivals(t), 0, nodeCount);
            }
            for (int i = 0; i < nodeCount; i++) {
                if (arriving[i] != 0) {
                    onHand[i] += arriving[i];
                    inTransit[i] -= arriving[i];
                    arriving[i] = 0;
                }
            }

            // ② Availability from events and random failures.
            boolean disrupted = availability.apply(t, nodeAvailability, linkAvailability);
            if (elements != null) {
                System.arraycopy(nodeAvailability, 0, elements.nodeAvailability(t), 0, nodeCount);
                System.arraycopy(linkAvailability, 0, elements.linkAvailability(t), 0,
                        network.linkCount());
            }

            // ③ Demand realisation. One generator for the period, consumed in
            // customer order, so the paired baseline replication realises exactly the same demand.
            Arrays.fill(demand, 0);
            SplittableRandom demandRandom = rng.stream(RngStream.DEMAND, t);
            double totalDemand = 0;
            for (int customer : network.customers()) {
                double realised = network.demand(customer)
                        * ReplicationRng.demandFactor(demandRandom, params.demandNoiseCv());
                demand[customer] = realised;
                totalDemand += realised;
            }

            // ④ Allocation — one minimum-cost flow.
            FlowSolution solution =
                    allocator.allocate(nodeAvailability, linkAvailability, onHand, inTransit, demand);
            if (elements != null) {
                // The solution is a local and is gone at the end of the period; served, throughput
                // and the link flows are only readable here.
                System.arraycopy(solution.served(), 0, elements.served(t), 0, nodeCount);
                System.arraycopy(solution.throughput(), 0, elements.throughput(t), 0, nodeCount);
                System.arraycopy(solution.linkFlow(), 0, elements.flow(t), 0, network.linkCount());
                double[] unserved = elements.unserved(t);
                for (int customer : network.customers()) {
                    unserved[customer] =
                            Math.max(0, demand[customer] - solution.served()[customer]);
                }
                Arrays.fill(leadWeighted, 0);
                Arrays.fill(dispatchedToward, 0);
            }

            // ⑤ Inventory update. See the class note for the identity.
            for (int i = 0; i < nodeCount; i++) {
                onHand[i] = Math.max(0, onHand[i] - solution.stockDrawn()[i] + solution.heldNow()[i]);
                double dwelling = solution.heldLater()[i];
                if (dwelling > 0) {
                    schedule(pipeline, inTransit, depth, t, network.processingPeriods(i), i, dwelling);
                }
            }
            for (int e = 0; e < network.linkCount(); e++) {
                long lead = network.leadTimePeriods(e);
                double shipped = solution.linkFlow()[e];
                if (shipped > 0 && network.linkIsUsable(e)) {
                    int target = network.linkTarget(e);
                    if (elements != null) {
                        // The one point where the arc, its flow, its lead and its target coincide.
                        // Transport lead only — the target's own processing dwell is not part of how
                        // long its inbound material travelled. A lead-0 arc contributes
                        // its flow at a weight of 0, which is what makes this a dispatch-weighted
                        // mean rather than a mean over arcs.
                        leadWeighted[target] += shipped * lead;
                        dispatchedToward[target] += shipped;
                    }
                    if (lead > 0) {
                        schedule(pipeline, inTransit, depth, t, lead, target, shipped);
                    }
                }
            }

            // ⑤b The per-element trace of the settled period (FR-18). After the two loops
            // above, because onHand is finalised by the first and inTransit by the second.
            if (elements != null) {
                System.arraycopy(onHand, 0, elements.onHand(t), 0, nodeCount);
                System.arraycopy(inTransit, 0, elements.inTransit(t), 0, nodeCount);
                double[] inboundLead = elements.inboundLead(t);
                for (int i = 0; i < nodeCount; i++) {
                    inboundLead[i] = dispatchedToward[i] > 0
                            ? leadWeighted[i] / dispatchedToward[i]
                            : ElementTrace.ABSENT;
                }
                double[] utilisation = elements.utilisation(t);
                for (int e = 0; e < network.linkCount(); e++) {
                    utilisation[e] = utilisation(e, solution.linkFlow()[e], linkAvailability[e]);
                }
            }

            // ⑥ The trace.
            double endingInventory = 0;
            double holdingCost = 0;
            double pipelineTotal = 0;
            for (int i = 0; i < nodeCount; i++) {
                endingInventory += onHand[i];
                holdingCost += onHand[i] * network.holdingCost(i);
                pipelineTotal += inTransit[i];
            }
            double shortageCost = 0;
            for (int customer : network.customers()) {
                double unserved = Math.max(0, demand[customer] - solution.served()[customer]);
                // Priced at what the customer's product is worth (product.unit_value), or at
                // the run's override. This is the economic cost of the shortage; it is deliberately
                // not the penalty the flow problem used to decide the routing — see FlowAllocator.
                shortageCost += unserved * penaltyFor(customer);
            }

            periods.add(new PeriodTrace(t, totalDemand, solution.totalServed(), endingInventory,
                    pipelineTotal, network.totalFixedCostPerPeriod(), solution.variableCost(),
                    solution.transportCost(), holdingCost, shortageCost, disrupted));
        }

        return new ReplicationOutcome(new ReplicationTrace(replicationIndex, params.seed(), baseline,
                availability.onsetPeriod(), periods), elements);
    }

    /**
     * How much of an arc's available capacity its flow used this period (FR-18).
     *
     * <p>The denominator is the capacity <em>the solver actually had</em> — the nominal ceiling after
     * the period's availability multiplier, taken through the same fixed-point floor
     * {@link FlowAllocator} builds the arc with. Any other denominator would let a saturated arc
     * report 0.999: a network whose capacities do not land on {@link Quantiser}'s grid loses the
     * residual, and a reader asking "was this link the bottleneck?" would get "nearly".
     *
     * <p>{@link ElementTrace#ABSENT} in three cases, and each is a statement rather than a gap:
     * an arc with no declared capacity has no fraction to be at ("an unconstrained element cannot be
     * partly disrupted" — {@link FlowAllocator#adjusted}); an arc an outage has taken to zero
     * capacity is dark, not idle, and {@code 0/0} is not 0; and an arc with a missing endpoint was
     * never built into the flow problem at all.
     */
    private double utilisation(int link, double flow, double availability) {
        if (!network.linkIsUsable(link)) {
            return ElementTrace.ABSENT;
        }
        double nominal = network.linkCapacity(link);
        if (Double.isInfinite(nominal)) {
            return ElementTrace.ABSENT;
        }
        double available =
                quantiser.toQuantity(quantiser.floorUnits(FlowAllocator.adjusted(nominal, availability)));
        return available > 0 ? flow / available : ElementTrace.ABSENT;
    }

    /** Places a shipment in the slot it arrives in, and records it as in transit until it does. */
    private static void schedule(double[][] pipeline, double[] inTransit, int depth, int period,
            long delay, int target, double quantity) {
        pipeline[(int) ((period + delay) % depth)][target] += quantity;
        inTransit[target] += quantity;
    }

    /** What one unit of unmet demand at this customer costs ({@code TOTAL_COST}). */
    private double penaltyFor(int customer) {
        Double override = params.unmetDemandPenalty();
        return override != null ? override : network.shortagePenalty(customer);
    }
}
