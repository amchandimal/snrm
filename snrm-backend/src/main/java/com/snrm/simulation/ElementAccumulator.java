package com.snrm.simulation;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds each finished replication's {@link ElementTrace} into per-period sums, and divides them out
 * once at the end (FR-18).
 *
 * <h2>Fold and discard</h2>
 *
 * <p>The whole reason this class exists. A replication's element history is
 * {@code H × (N + E)} numbers, and a run may have hundreds of replications: keeping them all would
 * make the per-element series cost {@code R} times what the aggregate curve costs, on a run whose
 * result is a single mean. So a replication is added into these sums the moment it finishes and the
 * trace becomes garbage immediately — memory here is {@code O(H × (N + E))} <strong>independent of
 * the replication count</strong>, and the peak is that plus however many replications are in flight
 * at once.
 *
 * <p><strong>Sums now, means once.</strong> Dividing per replication would accumulate {@code R}
 * roundings into every cell; summing and dividing at the end accumulates one. {@link #mean()} is
 * called exactly once, after the fan-out has joined.
 *
 * <h2>One lock, one acquisition per replication</h2>
 *
 * <p>Completions arrive from every virtual thread of the fan-out, so the folds are
 * {@code synchronized} — at one acquisition per <em>replication</em>, thousands of flow solves
 * apart, which is not a number worth designing against. This is deliberately the same trade
 * {@code MonteCarloRunner.StreamingFigures} makes, and the two fold at the same points in the same
 * task bodies so a reader has one place to look for "what happens when a replication finishes".
 *
 * <h2>Two quantities are means over a subset</h2>
 *
 * <p>{@code inboundLead} and {@code utilisation} can be undefined in a period
 * ({@link ElementTrace#ABSENT}), so each carries a count of the replications that actually defined
 * it and is divided by that count rather than by the replication total. A period no replication
 * defined stays absent. Averaging a defined value against an assumed zero would report a link at
 * half utilisation because half the replications had it switched off — which is exactly the
 * "absent renders absent, never zero" failure the metric suite already guards against.
 */
final class ElementAccumulator {

    private final long[] nodeIds;
    private final long[] linkIds;
    private final int horizon;
    private final int nodeCount;
    private final int linkCount;

    // Sums, period-major to match ElementTrace so a fold is a straight double loop. Transposed to
    // element-major once, in mean().
    private final double[][] onHand;
    private final double[][] inTransit;
    private final double[][] arrivals;
    private final double[][] served;
    private final double[][] unserved;
    private final double[][] throughput;
    private final double[][] nodeAvailability;
    private final double[][] inboundLead;
    private final int[][] inboundLeadCount;

    private final double[][] flow;
    private final double[][] utilisation;
    private final int[][] utilisationCount;
    private final double[][] linkAvailability;

    private final double[][] baselineOnHand;
    private final double[][] baselineServed;
    private final double[][] baselineFlow;

    private int disruptedFolds;
    private int baselineFolds;

    private ElementAccumulator(SimulationNetwork network, int horizon) {
        this.horizon = horizon;
        this.nodeCount = network.nodeCount();
        this.linkCount = network.linkCount();
        this.nodeIds = new long[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodeIds[i] = network.nodeId(i);
        }
        this.linkIds = new long[linkCount];
        for (int e = 0; e < linkCount; e++) {
            linkIds[e] = network.linkId(e);
        }
        this.onHand = new double[horizon][nodeCount];
        this.inTransit = new double[horizon][nodeCount];
        this.arrivals = new double[horizon][nodeCount];
        this.served = new double[horizon][nodeCount];
        this.unserved = new double[horizon][nodeCount];
        this.throughput = new double[horizon][nodeCount];
        this.nodeAvailability = new double[horizon][nodeCount];
        this.inboundLead = new double[horizon][nodeCount];
        this.inboundLeadCount = new int[horizon][nodeCount];
        this.flow = new double[horizon][linkCount];
        this.utilisation = new double[horizon][linkCount];
        this.utilisationCount = new int[horizon][linkCount];
        this.linkAvailability = new double[horizon][linkCount];
        this.baselineOnHand = new double[horizon][nodeCount];
        this.baselineServed = new double[horizon][nodeCount];
        this.baselineFlow = new double[horizon][linkCount];
    }

    /** An accumulator sized for one run's snapshot and horizon. */
    static ElementAccumulator of(SimulationNetwork network, int horizonPeriods) {
        return new ElementAccumulator(network, horizonPeriods);
    }

    /** The trace shape a replication of this run must produce. */
    ElementTrace newTrace() {
        return new ElementTrace(horizon, nodeCount, linkCount);
    }

    /**
     * A disrupted replication finished: add every quantity into the sums.
     *
     * <p>Called at exactly the point {@code StreamingFigures.disruptedDone} is called, from the
     * task body, so the trace is dropped on the same statement it is consumed on.
     */
    synchronized void foldDisrupted(ElementTrace trace) {
        disruptedFolds++;
        for (int t = 0; t < horizon; t++) {
            add(onHand[t], trace.onHand(t));
            add(inTransit[t], trace.inTransit(t));
            add(arrivals[t], trace.arrivals(t));
            add(served[t], trace.served(t));
            add(unserved[t], trace.unserved(t));
            add(throughput[t], trace.throughput(t));
            add(nodeAvailability[t], trace.nodeAvailability(t));
            addDefined(inboundLead[t], inboundLeadCount[t], trace.inboundLead(t));
            add(flow[t], trace.flow(t));
            add(linkAvailability[t], trace.linkAvailability(t));
            addDefined(utilisation[t], utilisationCount[t], trace.utilisation(t));
        }
    }

    /**
     * A baseline replication finished: add it into the three mirror sums and nothing else.
     *
     * <p>The undisrupted set exists to be measured <em>against</em>, so only the
     * quantities the schema keeps a baseline column for are folded. Everything else about a baseline
     * replication is the same question already answered by the disrupted set with the events
     * removed, and storing it would double the table for a curve nothing reads.
     */
    synchronized void foldBaseline(ElementTrace trace) {
        baselineFolds++;
        for (int t = 0; t < horizon; t++) {
            add(baselineOnHand[t], trace.onHand(t));
            add(baselineServed[t], trace.served(t));
            add(baselineFlow[t], trace.flow(t));
        }
    }

    /**
     * Divides the sums out and transposes to the element-major shape the tables and the API use.
     *
     * <p><strong>The baseline-run copy-in.</strong> When no baseline replication was folded — the
     * baseline run of FR-17, where {@code MonteCarloRunner} skips the pairing because an undisrupted
     * run has no disruption to isolate — the three baseline arrays take the run's own disrupted
     * means. That is definitionally true rather than a stand-in: the paired set, had it run, would
     * have drawn identically. It is the same convention {@code SimulationRunWriter.aggregate} takes
     * for {@code baseline_served_demand} and {@code baseline_cost}, and taking a different one here
     * would make the network curve and the element curves disagree about the same run.
     */
    synchronized ElementSeries mean() {
        List<ElementSeries.NodeSeries> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            double[] nodeOnHand = meanByPeriod(onHand, i, disruptedFolds);
            double[] nodeServed = meanByPeriod(served, i, disruptedFolds);
            nodes.add(new ElementSeries.NodeSeries(
                    nodeIds[i],
                    nodeOnHand,
                    meanByPeriod(inTransit, i, disruptedFolds),
                    meanByPeriod(arrivals, i, disruptedFolds),
                    nodeServed,
                    meanByPeriod(unserved, i, disruptedFolds),
                    meanByPeriod(throughput, i, disruptedFolds),
                    meanByPeriod(nodeAvailability, i, disruptedFolds),
                    meanOfDefined(inboundLead, inboundLeadCount, i),
                    baselineFolds == 0 ? nodeOnHand.clone()
                            : meanByPeriod(baselineOnHand, i, baselineFolds),
                    baselineFolds == 0 ? nodeServed.clone()
                            : meanByPeriod(baselineServed, i, baselineFolds)));
        }

        List<ElementSeries.LinkSeries> links = new ArrayList<>(linkCount);
        for (int e = 0; e < linkCount; e++) {
            double[] linkFlow = meanByPeriod(flow, e, disruptedFolds);
            links.add(new ElementSeries.LinkSeries(
                    linkIds[e],
                    linkFlow,
                    meanOfDefined(utilisation, utilisationCount, e),
                    meanByPeriod(linkAvailability, e, disruptedFolds),
                    baselineFolds == 0 ? linkFlow.clone()
                            : meanByPeriod(baselineFlow, e, baselineFolds)));
        }
        return new ElementSeries(horizon, nodes, links);
    }

    /** One element's column of a period-major sum table, divided by the replications that fed it. */
    private double[] meanByPeriod(double[][] sums, int element, int folds) {
        double[] series = new double[horizon];
        if (folds == 0) {
            return series;
        }
        for (int t = 0; t < horizon; t++) {
            series[t] = sums[t][element] / folds;
        }
        return series;
    }

    /** The same, over only the replications that defined the value; absent where none did. */
    private double[] meanOfDefined(double[][] sums, int[][] counts, int element) {
        double[] series = new double[horizon];
        for (int t = 0; t < horizon; t++) {
            int defined = counts[t][element];
            series[t] = defined == 0 ? ElementTrace.ABSENT : sums[t][element] / defined;
        }
        return series;
    }

    private static void add(double[] sums, double[] row) {
        for (int i = 0; i < sums.length; i++) {
            sums[i] += row[i];
        }
    }

    /** Adds only the entries this replication defined, counting them so the divisor matches. */
    private static void addDefined(double[] sums, int[] counts, double[] row) {
        for (int i = 0; i < sums.length; i++) {
            if (!Double.isNaN(row[i])) {
                sums[i] += row[i];
                counts[i]++;
            }
        }
    }
}
