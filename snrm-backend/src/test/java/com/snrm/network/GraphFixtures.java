package com.snrm.network;

import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeBasis;
import com.snrm.common.TimeUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link NetworkGraph} snapshots in memory, for the engine tests.
 *
 * <blockquote>"engine correctness via analytically solvable micro-networks (e.g. a 3-node chain
 * whose TTR and loss area are hand-computable)"</blockquote>
 *
 * <p><strong>Why this exists rather than a Spring slice.</strong> Every metric and every
 * step of the simulation loop is a function of the snapshot and of nothing else — that is
 * the isolation the snapshot buys — so a test that went through JPA to produce one would be testing
 * the mapper, slowly, and would need Docker to do it. This class is the shortest path from "a
 * five-node network with these capacities" to the object the engines actually see.
 *
 * <p>It lives in {@code com.snrm.network} because {@link NetworkGraph}'s constructor is
 * package-private, and deliberately stays that way: {@link NetworkGraphFactory} is the only
 * production builder, and widening the constructor so a test in another package could reach it would
 * trade a real guarantee for a convenience.
 *
 * <p><strong>Ids are assigned in insertion order, from 1.</strong> Nodes and links each get their own
 * sequence, mirroring what the importer does with the rows of a CSV file. That matters in exactly one
 * place — {@code ROBUSTNESS_TARGETED} breaks criticality ties by node id — so a fixture's declaration
 * order is part of what it asserts.
 *
 * <p>Everything here is already in <em>periods</em> and <em>per-period</em> quantities, because that
 * is what a snapshot holds. No unit ever appears; the conversion is
 * {@code NetworkGraphFactory}'s job and is tested elsewhere.
 *
 * <h2>The two families of setter</h2>
 *
 * <p>The topological suite needs structure and capacity, so {@code supplier}, {@code plant},
 * {@code dc}, {@code customer} and {@code link} take everything it reads. The simulation engine also
 * needs costs, stock and failure probabilities, which would make those signatures unreadable — so
 * they are fluent modifiers applied to the <em>most recently added</em> element:
 * {@code .plant("PLANT-1", 80.0).costs(200, 3).stock(100, 0, 0.5)}.
 */
public final class GraphFixtures {

    /** The one product every fixture node with demand carries. Metrics sum over products. */
    private static final long PRODUCT_ID = 1;
    private static final String PRODUCT_NAME = "WIDGET-A";

    private GraphFixtures() {
    }

    /** A fresh builder on a 1-day / 52-period / NEAREST clock. */
    public static Builder network() {
        return new Builder();
    }

    /**
     * The six-node network of {@code docs/metric-verification.md} and
     * {@code samples/metric-verification-6-node/}.
     *
     * <p>Kept here rather than in one test so that every test asserting against that document works
     * from a single definition of it. If this method and the sample CSVs ever disagree, the metric
     * suite is being verified against something that is not the network the document describes.
     */
    public static NetworkGraph verificationNetwork() {
        return network()
                .supplier("SUP-1", 500.0)
                .plant("PLANT-1", 400.0)
                .dc("DC-1", 350.0)
                .dc("DC-2", 350.0)
                .customer("CUST-1", 40)
                .customer("CUST-2", 25)
                .link("SUP-1", "PLANT-1", 500.0, 2)
                .link("PLANT-1", "DC-1", 300.0, 3)
                .link("PLANT-1", "DC-2", 300.0, 4)
                .link("DC-1", "DC-2", 100.0, 1)
                .link("DC-1", "CUST-1", 200.0, 1)
                .link("DC-2", "CUST-2", 200.0, 1)
                .link("DC-1", "CUST-2", 150.0, 2)
                .build();
    }

    /**
     * The three-node chain of {@code docs/simulation-verification.md} and
     * {@code samples/simulation-verification-3-node/}.
     *
     * <p>Same rule as {@link #verificationNetwork()}: one definition, so a test asserting against
     * that document cannot be asserting against a different network. Every number here is read
     * directly off §1.1 and §1.2 of the document.
     */
    public static NetworkGraph simulationChain() {
        return network()
                .horizon(10)
                .supplier("SUP-1", 100.0).costs(100, 2).stock(0, 0, 0.5)
                .plant("PLANT-1", 80.0).costs(200, 3).stock(100, 0, 0.5)
                .customer("CUST-1", 50).unitValue(20)
                .link("SUP-1", "PLANT-1", 100.0, 0).linkCost(1)
                .link("PLANT-1", "CUST-1", 100.0, 0).linkCost(1)
                .build();
    }

    /** Fluent, and deliberately small: the tests should read as the network they describe. */
    public static final class Builder {

        private final List<GraphNode> nodes = new ArrayList<>();
        private final List<GraphLink> links = new ArrayList<>();
        private final Map<String, Long> idsByName = new LinkedHashMap<>();
        private TimeBasis basis = TimeBasis.of(1, TimeUnit.DAY, RoundingPolicy.NEAREST);
        private int horizonPeriods = 52;

        /** A SUPPLIER with no capacity ceiling. */
        public Builder supplier(String name) {
            return node(name, NodeType.SUPPLIER, null, 0);
        }

        public Builder supplier(String name, Double capacityPerPeriod) {
            return node(name, NodeType.SUPPLIER, capacityPerPeriod, 0);
        }

        public Builder plant(String name) {
            return node(name, NodeType.PLANT, null, 0);
        }

        public Builder plant(String name, Double capacityPerPeriod) {
            return node(name, NodeType.PLANT, capacityPerPeriod, 0);
        }

        public Builder dc(String name) {
            return node(name, NodeType.DC, null, 0);
        }

        public Builder dc(String name, Double capacityPerPeriod) {
            return node(name, NodeType.DC, capacityPerPeriod, 0);
        }

        /** A CUSTOMER with per-period demand and no capacity ceiling — the usual case. */
        public Builder customer(String name, double demandPerPeriod) {
            return node(name, NodeType.CUSTOMER, null, demandPerPeriod);
        }

        public Builder customer(String name, double demandPerPeriod, Double capacityPerPeriod) {
            return node(name, NodeType.CUSTOMER, capacityPerPeriod, demandPerPeriod);
        }

        /**
         * @param capacityPerPeriod throughput ceiling, or null for unconstrained
         * @param demandPerPeriod   per-period demand; only CUSTOMER demand reaches the metrics,
         *                          which is itself worth being able to set on a plant
         */
        public Builder node(String name, NodeType type, Double capacityPerPeriod,
                double demandPerPeriod) {
            if (idsByName.containsKey(name)) {
                throw new IllegalArgumentException("duplicate node name in fixture: " + name);
            }
            long id = nodes.size() + 1L;
            idsByName.put(name, id);
            // A product row only where there is demand, so the topological fixtures keep exactly the
            // shape they had before the simulation engine existed. The modifiers below create one on
            // demand for the nodes that need stock or a unit value.
            List<GraphNodeProduct> products = demandPerPeriod == 0
                    ? List.of()
                    : List.of(new GraphNodeProduct(PRODUCT_ID, PRODUCT_NAME, demandPerPeriod,
                            0, 0, 0, 0));
            nodes.add(new GraphNode(id, name, type, capacityPerPeriod, 0, 0, 0, 0, null, products));
            return this;
        }

        // ------------------------------------------------- modifiers on the last node

        /** Fixed and variable cost of the node just added. */
        public Builder costs(double fixedCost, double varCost) {
            return replaceLastNode(node -> new GraphNode(node.id(), node.name(), node.type(),
                    node.capacityPerPeriod(), node.processingPeriods(), fixedCost, varCost,
                    node.failureProb(), node.region(), node.products()));
        }

        /** Opening stock, safety stock and holding rate of the node just added. */
        public Builder stock(double initialInventory, double safetyStock, double holdingCost) {
            return replaceLastProduct(product -> new GraphNodeProduct(product.productId(),
                    product.productName(), product.demandPerPeriod(), initialInventory, safetyStock,
                    holdingCost, product.unitValue()));
        }

        /** What one unit is worth — the price of unmet demand at this node. */
        public Builder unitValue(double unitValue) {
            return replaceLastProduct(product -> new GraphNodeProduct(product.productId(),
                    product.productName(), product.demandPerPeriod(), product.initialInventory(),
                    product.safetyStock(), product.holdingCostPerPeriod(), unitValue));
        }

        /** Dwell before material entering the node just added can leave it. */
        public Builder processing(long periods) {
            return replaceLastNode(node -> new GraphNode(node.id(), node.name(), node.type(),
                    node.capacityPerPeriod(), periods, node.fixedCost(), node.varCost(),
                    node.failureProb(), node.region(), node.products()));
        }

        /** Per-period failure probability of the node just added. */
        public Builder failureProb(double probability) {
            return replaceLastNode(node -> new GraphNode(node.id(), node.name(), node.type(),
                    node.capacityPerPeriod(), node.processingPeriods(), node.fixedCost(),
                    node.varCost(), probability, node.region(), node.products()));
        }

        /** Region tag of the node just added — what a REGION-scoped event resolves through. */
        public Builder region(String region) {
            return replaceLastNode(node -> new GraphNode(node.id(), node.name(), node.type(),
                    node.capacityPerPeriod(), node.processingPeriods(), node.fixedCost(),
                    node.varCost(), node.failureProb(), region, node.products()));
        }

        // ------------------------------------------------------------------ links

        /** An arc with no capacity ceiling and one period of transit. */
        public Builder link(String source, String target) {
            return link(source, target, null, 1);
        }

        public Builder link(String source, String target, Double capacityPerPeriod) {
            return link(source, target, capacityPerPeriod, 1);
        }

        public Builder link(String source, String target, Double capacityPerPeriod,
                long leadTimePeriods) {
            long id = links.size() + 1L;
            links.add(new GraphLink(id, id(source), id(target), leadTimePeriods, capacityPerPeriod,
                    0, 0));
            return this;
        }

        /** Unit cost of the arc just added. */
        public Builder linkCost(double unitCost) {
            return replaceLastLink(link -> new GraphLink(link.id(), link.sourceNodeId(),
                    link.targetNodeId(), link.leadTimePeriods(), link.capacityPerPeriod(), unitCost,
                    link.failureProb()));
        }

        /** Per-period failure probability of the arc just added. */
        public Builder linkFailureProb(double probability) {
            return replaceLastLink(link -> new GraphLink(link.id(), link.sourceNodeId(),
                    link.targetNodeId(), link.leadTimePeriods(), link.capacityPerPeriod(),
                    link.unitCost(), probability));
        }

        // ------------------------------------------------------------------ clock

        /** Overrides the default 1-day / NEAREST clock. No topological metric depends on it. */
        public Builder clock(double periodValue, TimeUnit unit, RoundingPolicy policy) {
            this.basis = TimeBasis.of(periodValue, unit, policy);
            return this;
        }

        public Builder horizon(int periods) {
            this.horizonPeriods = periods;
            return this;
        }

        /** The id the builder assigned to a node, for asserting against a scoped metric value. */
        public long id(String name) {
            Long id = idsByName.get(name);
            if (id == null) {
                throw new IllegalArgumentException("no node named '" + name + "' in this fixture");
            }
            return id;
        }

        public NetworkGraph build() {
            return new NetworkGraph(1, "Fixture", 1, basis, horizonPeriods, nodes, links);
        }

        // ---------------------------------------------------------------- internals

        private Builder replaceLastNode(java.util.function.UnaryOperator<GraphNode> change) {
            requireLast(nodes.isEmpty(), "node");
            nodes.set(nodes.size() - 1, change.apply(nodes.get(nodes.size() - 1)));
            return this;
        }

        private Builder replaceLastLink(java.util.function.UnaryOperator<GraphLink> change) {
            requireLast(links.isEmpty(), "link");
            links.set(links.size() - 1, change.apply(links.get(links.size() - 1)));
            return this;
        }

        /** Creates the node's single product row if it has none, then rewrites it. */
        private Builder replaceLastProduct(
                java.util.function.UnaryOperator<GraphNodeProduct> change) {
            return replaceLastNode(node -> {
                GraphNodeProduct existing = node.products().isEmpty()
                        ? new GraphNodeProduct(PRODUCT_ID, PRODUCT_NAME, 0, 0, 0, 0, 0)
                        : node.products().get(0);
                return new GraphNode(node.id(), node.name(), node.type(), node.capacityPerPeriod(),
                        node.processingPeriods(), node.fixedCost(), node.varCost(),
                        node.failureProb(), node.region(), List.of(change.apply(existing)));
            });
        }

        private static void requireLast(boolean empty, String kind) {
            if (empty) {
                throw new IllegalStateException(
                        "no " + kind + " has been added yet, so there is nothing to modify");
            }
        }
    }
}
