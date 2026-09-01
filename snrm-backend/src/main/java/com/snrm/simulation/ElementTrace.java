package com.snrm.simulation;

/**
 * One replication's per-element history: what every node and every link did in every period
 * (FR-18).
 *
 * <p>The element-level companion to {@link PeriodTrace}, and deliberately <em>not</em> part of it.
 * A {@code PeriodTrace} is retained for the whole run — {@link SimulationTraces} holds every
 * replication's list so the metric suite can re-read it — whereas this object is
 * <strong>folded and discarded</strong>: {@link ElementAccumulator} adds it into per-period sums the
 * moment the replication finishes and the trace becomes garbage. That is what keeps a 200-replication
 * run's element memory at {@code O(H × (N + E))} rather than {@code O(R × H × (N + E))}.
 *
 * <h2>Shape</h2>
 *
 * <p>Primitive arrays sized {@code [horizon][nodeCount]} and {@code [horizon][linkCount]}, allocated
 * once per replication and written a row at a time by {@link SimulationEngine}. Period-major because
 * that is the order the loop produces them in, so every capture is one {@code System.arraycopy} out
 * of state the loop is already holding. {@link ElementAccumulator} transposes to element-major once,
 * at the end, where the cost is paid a single time instead of {@code R} times.
 *
 * <p>Each row is written exactly once and starts at zero, so nothing here is ever cleared: a
 * quantity the period did not produce — {@code unserved} at a node that is not a customer, say — is
 * a true zero rather than a stale value.
 *
 * <h2>{@link #ABSENT}</h2>
 *
 * <p>Two quantities can be genuinely undefined in a period, and carry {@code NaN} when they are:
 *
 * <ul>
 *   <li>{@code inboundLead} — nothing was dispatched toward that node, so there is no
 *       dispatch-weighted mean to take. Zero would claim instantaneous delivery.</li>
 *   <li>{@code utilisation} — the link declares no capacity ("an unconstrained element cannot be
 *       partly disrupted", {@link FlowAllocator#adjusted}), or an event has taken its available
 *       capacity to zero, where {@code 0/0} is undefined and reporting 0 would say the link was idle
 *       rather than dark.</li>
 * </ul>
 *
 * <p>{@code NaN} rather than a boxed {@code Double} because these arrays are written once per period
 * per replication and a {@code Double[]} would allocate one object per cell. The sentinel travels as
 * far as {@code ElementSeries} and becomes a JSON {@code null} at the API edge, which is the same
 * "absent renders absent, never zero" rule the metric suite follows.
 */
final class ElementTrace {

    /** An undefined quantity — see the class note. Compare with {@link Double#isNaN(double)}. */
    static final double ABSENT = Double.NaN;

    private final int horizon;
    private final int nodeCount;
    private final int linkCount;

    private final double[][] onHand;
    private final double[][] inTransit;
    private final double[][] arrivals;
    private final double[][] served;
    private final double[][] unserved;
    private final double[][] throughput;
    private final double[][] nodeAvailability;
    private final double[][] inboundLead;

    private final double[][] flow;
    private final double[][] utilisation;
    private final double[][] linkAvailability;

    ElementTrace(int horizon, int nodeCount, int linkCount) {
        this.horizon = horizon;
        this.nodeCount = nodeCount;
        this.linkCount = linkCount;
        this.onHand = new double[horizon][nodeCount];
        this.inTransit = new double[horizon][nodeCount];
        this.arrivals = new double[horizon][nodeCount];
        this.served = new double[horizon][nodeCount];
        this.unserved = new double[horizon][nodeCount];
        this.throughput = new double[horizon][nodeCount];
        this.nodeAvailability = new double[horizon][nodeCount];
        this.inboundLead = new double[horizon][nodeCount];
        this.flow = new double[horizon][linkCount];
        this.utilisation = new double[horizon][linkCount];
        this.linkAvailability = new double[horizon][linkCount];
    }

    int horizon() {
        return horizon;
    }

    int nodeCount() {
        return nodeCount;
    }

    int linkCount() {
        return linkCount;
    }

    // The row accessors the engine writes into. Returned rather than copied so a capture is one
    // arraycopy: this runs once per period per replication, and on a 1,000-node network with a
    // 52-period horizon it is the only allocation-free way to record the loop's own state.

    double[] onHand(int period) {
        return onHand[period];
    }

    double[] inTransit(int period) {
        return inTransit[period];
    }

    double[] arrivals(int period) {
        return arrivals[period];
    }

    double[] served(int period) {
        return served[period];
    }

    double[] unserved(int period) {
        return unserved[period];
    }

    double[] throughput(int period) {
        return throughput[period];
    }

    double[] nodeAvailability(int period) {
        return nodeAvailability[period];
    }

    double[] inboundLead(int period) {
        return inboundLead[period];
    }

    double[] flow(int period) {
        return flow[period];
    }

    double[] utilisation(int period) {
        return utilisation[period];
    }

    double[] linkAvailability(int period) {
        return linkAvailability[period];
    }
}
