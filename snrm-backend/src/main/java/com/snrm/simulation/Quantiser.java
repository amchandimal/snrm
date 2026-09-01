package com.snrm.simulation;

/**
 * Fixed-point conversion between the model's continuous quantities and the integers JGraphT's
 * minimum-cost flow requires.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code MinimumCostFlowProblem} takes {@code Function<V,Integer>} node supplies and
 * {@code Function<E,Integer>} arc capacities — costs stay {@code double}, but the quantities do not.
 * SNRM's quantities are continuous by construction: a capacity of 120 per week on a one-day period
 * is 17.142857… per period after the conversion, and demand noise produces
 * arbitrary reals. Something has to reconcile the two, and doing it implicitly with a cast would
 * silently floor a network's every capacity.
 *
 * <p>So quantities are carried in <strong>milli-units</strong> by default: one unit is
 * {@link SimulationParams#quantum()} integer steps, and the residual is at most half a step per
 * rounded quantity. On a network of a few hundred arcs that is a per-period error below 0.5 units in
 * total against demands measured in hundreds — four orders of magnitude beneath anything a
 * resilience conclusion rests on, and it is stated in {@code docs/simulation-verification.md} rather
 * than left to be discovered.
 *
 * <h2>The two roundings are deliberately different</h2>
 *
 * <ul>
 *   <li><strong>Capacities round down.</strong> A ceiling that has been rounded up is a capacity the
 *       network does not have, and a resilience study that overstates capacity understates
 *       disruption — the one direction the tool must not err in.</li>
 *   <li><strong>Demand rounds to nearest.</strong> Demand is the denominator of every service
 *       metric, and biasing it in either direction would bias {@code FILL_RATE} for every
 *       network whose demand does not land on the grid.</li>
 * </ul>
 *
 * <h2>Overflow</h2>
 *
 * <p>A super-source's supply is the whole network's demand plus its safety-stock shortfall, times
 * the quantum, and it has to fit in an {@code int}. {@link #of} therefore coarsens the quantum —
 * halving it until it fits — rather than letting a large network silently wrap into a negative
 * supply. A run that hits this is enormous by research standards, and the coarsening is logged by
 * the caller; the alternative, refusing the run, would be a worse answer than a slightly coarser
 * grid.
 */
public final class Quantiser {

    /**
     * The capacity JGraphT reads as "unbounded".
     *
     * <p>{@code CapacityScalingMinimumCostFlow.CAP_INF} is {@code Integer.MAX_VALUE}; using a
     * headroom-bounded value instead keeps every intermediate sum inside the {@code int} range,
     * which {@code Integer.MAX_VALUE} on two arcs into one vertex would not.
     */
    public static final int UNBOUNDED = Integer.MAX_VALUE / 4;

    private final int quantum;

    private Quantiser(int quantum) {
        this.quantum = quantum;
    }

    /**
     * A quantiser at the requested resolution, coarsened if the network's total flow would overflow.
     *
     * @param requestedQuantum {@link SimulationParams#quantum()}
     * @param maxQuantity      the largest quantity that will be quantised — the network's total
     *                         per-period demand plus its total safety stock, with headroom
     */
    public static Quantiser of(int requestedQuantum, double maxQuantity) {
        int quantum = Math.max(1, requestedQuantum);
        while (quantum > 1 && maxQuantity * quantum > UNBOUNDED) {
            quantum = Math.max(1, quantum / 2);
        }
        return new Quantiser(quantum);
    }

    /** Integer steps per unit — 1000 means quantities are carried to the nearest thousandth. */
    public int quantum() {
        return quantum;
    }

    /** A capacity or supply ceiling, rounded <em>down</em>; never negative. See the class note. */
    public int floorUnits(double quantity) {
        if (Double.isNaN(quantity) || quantity <= 0) {
            return 0;
        }
        if (Double.isInfinite(quantity)) {
            return UNBOUNDED;
        }
        double scaled = Math.floor(quantity * quantum);
        return scaled >= UNBOUNDED ? UNBOUNDED : (int) scaled;
    }

    /** A demand or requirement, rounded to <em>nearest</em>; never negative. See the class note. */
    public int roundUnits(double quantity) {
        if (Double.isNaN(quantity) || quantity <= 0) {
            return 0;
        }
        if (Double.isInfinite(quantity)) {
            return UNBOUNDED;
        }
        double scaled = Math.rint(quantity * quantum);
        return scaled >= UNBOUNDED ? UNBOUNDED : (int) scaled;
    }

    /** Integer steps back to a quantity — the inverse every trace value passes through. */
    public double toQuantity(double units) {
        return units / quantum;
    }

    @Override
    public String toString() {
        return "Quantiser[1 unit = " + quantum + " steps]";
    }
}
