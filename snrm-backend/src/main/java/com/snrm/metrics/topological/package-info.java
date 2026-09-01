/**
 * The topological half of the metric suite: the structural metrics that need no simulation
 * and are recomputed as the network is edited (FR-04).
 *
 * <h2>What the whole family agrees on</h2>
 *
 * <p>Nine calculators over one immutable {@code NetworkGraph} snapshot. Four conventions are shared
 * by all of them, stated once here so each class can state only what is its own:
 *
 * <ul>
 *   <li><strong>Connectivity is weak.</strong> Where a metric speaks of a "connected component" it
 *       means a weakly connected one — the components of the graph with arc directions dropped.
 *       A supply network is layered and almost never strongly connected, so strong components would
 *       report the node count on every realistic network and measure nothing.</li>
 *   <li><strong>Supply originates at fixed origins.</strong> The supply-side nodes (SUPPLIER or
 *       PLANT, following {@code NetworkChecks}) that have no inbound arc: the points at
 *       which material enters the model. In a SUPPLIER→PLANT→DC→CUSTOMER network these are the
 *       suppliers; in one modelled from the factory gate outwards, which the model allows, they are
 *       the plants. <strong>The set is computed once on the intact graph and held fixed</strong>
 *       through every removal — otherwise deleting a supplier would promote the plant behind it into
 *       an origin, and the metric would report that losing a sole supplier costs nothing.</li>
 *   <li><strong>Demand sits on CUSTOMER nodes</strong> — {@code demand_value} applies to CUSTOMER
 *       nodes — and is the sum of that node's per-product per-period demand.
 *       Demand entered on a plant is ignored here exactly as the importer warns it will be.</li>
 *   <li><strong>Everything is in periods.</strong> Capacities and demands arrive already converted
 *       by the snapshot's {@code TimeBasis}, so every number below is per period and no calculator
 *       imports {@code TimeUnit}.</li>
 * </ul>
 *
 * <h2>Verifying them</h2>
 *
 * <p>{@code docs/metric-verification.md} works a six-node network through every metric here by hand,
 * one arithmetic step at a time, and states the exact value the API returns for it;
 * {@code samples/metric-verification-6-node/} is that network in the canonical import schema.
 * That document is what these classes are checked against — a disagreement
 * between the two is a defect in one of them, never a rounding difference to shrug at.
 */
package com.snrm.metrics.topological;
