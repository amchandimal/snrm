package com.snrm.dataimport;

import com.snrm.dataimport.StagedNetwork.StagedLink;
import com.snrm.dataimport.StagedNetwork.StagedNode;
import com.snrm.dataimport.StagedNetwork.StagedNodeProduct;
import com.snrm.network.NodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 2: the checks that are about the network rather than about any one row.
 *
 * <p>What it looks for: at least one supply-side node and one customer, no customer without an
 * inbound path, echelon-direction warnings (e.g. a {@code CUSTOMER→SUPPLIER} link is flagged), and
 * the time-resolution checks.
 *
 * <p>The time-resolution half is not here: it is {@code TimeValidationService.check} in the network
 * module, called by {@link ImportService} with the same staged durations. Reimplementing its table
 * for import would give the editor and the wizard two subtly different opinions about the
 * same 6-hour lead time, which is exactly the failure that service's pure entry point exists to
 * prevent.
 *
 * <p><strong>Where the errors are.</strong> Only three things here refuse an import: no supply, no
 * customer, and a customer nothing can reach. Each one makes every service metric read zero
 * for a purely structural reason, so a simulation over such a network measures the topology rather than
 * the disruption — and it would do so silently, which is the part that matters. Everything else is a
 * warning, because everything else is something a researcher may mean: a lateral DC-to-DC arc is
 * transshipment, a backwards arc is reverse logistics, an orphan node is a facility not yet
 * connected, and a demand on a plant is a row the engine will ignore.
 */
@Component
public class NetworkChecks {

    /**
     * The echelons that can originate material.
     *
     * <p>A PLANT counts alongside a SUPPLIER: a network modelled from the factory gate outwards is a
     * legitimate and common simplification, and refusing it would force a fictitious supplier row into
     * every such file. A DC does not count — it stocks and forwards, so a network of only DCs and
     * customers has inventory but no replenishment, and its fill rate would decay to zero by
     * construction.
     */
    private static final Set<NodeType> SUPPLY_SIDE = Set.of(NodeType.SUPPLIER, NodeType.PLANT);

    /** Every finding, in the order stage 2 asks its questions. */
    public List<ImportDiagnostic> check(StagedNetwork staged) {
        List<ImportDiagnostic> findings = new ArrayList<>();

        Map<String, StagedNode> nodesByKey = new LinkedHashMap<>();
        for (StagedNode node : staged.nodes()) {
            nodesByKey.put(RowValidator.key(node.name()), node);
        }
        if (nodesByKey.isEmpty()) {
            findings.add(ImportDiagnostic.network(ImportSheet.NODES, ImportSeverity.ERROR,
                    ImportCheck.NETWORK_CHECK_SKIPPED, null,
                    "No usable node rows, so nothing about the network's structure could be checked. "
                            + "Fix the row errors above first."));
            return findings;
        }

        presence(nodesByKey, findings);
        reachability(nodesByKey, staged.links(), findings);
        echelonDirection(nodesByKey, staged.links(), findings);
        connectivity(nodesByKey, staged.links(), findings);
        demand(nodesByKey, staged.nodeProducts(), findings);

        if (!staged.complete()) {
            findings.add(ImportDiagnostic.network(null, ImportSeverity.WARNING,
                    ImportCheck.NETWORK_CHECKS_ON_PARTIAL_GRAPH, null,
                    "Rows above were rejected, so these network-level findings describe the graph "
                            + "without them. Fix the row errors and validate again before reading "
                            + "them as final."));
        }
        return findings;
    }

    // ---------------------------------------------------------------------- presence

    private void presence(Map<String, StagedNode> nodes, List<ImportDiagnostic> findings) {
        boolean hasSupply = false;
        boolean hasCustomer = false;
        for (StagedNode node : nodes.values()) {
            hasSupply |= SUPPLY_SIDE.contains(node.type());
            hasCustomer |= node.type() == NodeType.CUSTOMER;
        }
        if (!hasSupply) {
            findings.add(ImportDiagnostic.network(ImportSheet.NODES, ImportSeverity.ERROR,
                    ImportCheck.NO_SUPPLY_NODE, null,
                    "The network has no SUPPLIER and no PLANT, so nothing in it originates material: "
                            + "no demand could ever be served and every service metric would read "
                            + "zero for a structural reason."));
        }
        if (!hasCustomer) {
            findings.add(ImportDiagnostic.network(ImportSheet.NODES, ImportSeverity.ERROR,
                    ImportCheck.NO_CUSTOMER_NODE, null,
                    "The network has no CUSTOMER, so there is no demand for a simulation to measure "
                            + "service against."));
        }
    }

    // ------------------------------------------------------------------ reachability

    /**
     * Every customer must have a directed path from some supply-side node.
     *
     * <p>Computed as one traversal <em>forwards</em> from every supply node at once, rather than a
     * search backwards per customer: the answer is the same and the cost is one pass over the graph
     * instead of one per customer, which matters at the 1,000-node scale of FR-04 where a fan-in of
     * hundreds of customers is normal. Intermediate echelons are traversed transparently, so a
     * customer fed through two DCs is reachable — the check is about the existence of a path, not its
     * length.
     */
    private void reachability(Map<String, StagedNode> nodes, List<StagedLink> links,
            List<ImportDiagnostic> findings) {

        Map<String, List<String>> outgoing = new HashMap<>();
        for (StagedLink link : links) {
            outgoing.computeIfAbsent(RowValidator.key(link.source()), key -> new ArrayList<>())
                    .add(RowValidator.key(link.target()));
        }

        Set<String> reached = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        for (Map.Entry<String, StagedNode> entry : nodes.entrySet()) {
            if (SUPPLY_SIDE.contains(entry.getValue().type()) && reached.add(entry.getKey())) {
                frontier.add(entry.getKey());
            }
        }
        while (!frontier.isEmpty()) {
            for (String next : outgoing.getOrDefault(frontier.poll(), List.of())) {
                if (reached.add(next)) {
                    frontier.add(next);
                }
            }
        }

        for (StagedNode node : nodes.values()) {
            if (node.type() != NodeType.CUSTOMER || reached.contains(RowValidator.key(node.name()))) {
                continue;
            }
            findings.add(ImportDiagnostic.network(ImportSheet.NODES, ImportSeverity.ERROR,
                    ImportCheck.CUSTOMER_UNREACHABLE, node.name(),
                    ("Customer '%s' has no inbound path from any SUPPLIER or PLANT, so its demand can "
                            + "never be served. A fill rate averaged over it would measure the "
                            + "topology rather than the disruption. Add the missing link, "
                            + "or drop the customer.").formatted(node.name()))
                    .atLine(node.line()));
        }
    }

    // --------------------------------------------------------------- echelon direction

    /**
     * Links that run against the echelon order of {@link NodeType}.
     *
     * <p>Both kinds are warnings, which is a deliberate difference from the editor, which blocks a
     * link into a SUPPLIER or out of a CUSTOMER <em>at the gesture level</em>, because a mouse drag
     * that produces one is almost certainly a slip. A row in a file is not a slip — someone wrote it —
     * and such a link is accordingly flagged, not refused. Reverse logistics and returns flows are
     * real network features, and refusing to import a network that models them would be the tool
     * imposing a modelling opinion it has not earned.
     */
    private void echelonDirection(Map<String, StagedNode> nodes, List<StagedLink> links,
            List<ImportDiagnostic> findings) {

        for (StagedLink link : links) {
            StagedNode source = nodes.get(RowValidator.key(link.source()));
            StagedNode target = nodes.get(RowValidator.key(link.target()));
            if (source == null || target == null) {
                continue; // an unresolved endpoint; stage 1 has already said so
            }
            String arc = link.source() + " → " + link.target();
            int direction = Integer.compare(target.type().ordinal(), source.type().ordinal());
            if (direction < 0) {
                findings.add(ImportDiagnostic.network(ImportSheet.LINKS, ImportSeverity.WARNING,
                        ImportCheck.ECHELON_DIRECTION, arc,
                        ("%s runs upstream: %s → %s is against the echelon order. Legal, and the "
                                + "editor will show it, but check it is not a swapped source and "
                                + "target.")
                                .formatted(arc, source.type(), target.type()))
                        .atLine(link.line()));
            } else if (direction == 0) {
                findings.add(ImportDiagnostic.network(ImportSheet.LINKS, ImportSeverity.WARNING,
                        ImportCheck.LATERAL_LINK, arc,
                        ("%s is a lateral link between two %s nodes — transshipment. Unconventional "
                                + "but legal, and flagged so it is a choice rather than a typo.")
                                .formatted(arc, source.type()))
                        .atLine(link.line()));
            }
        }
    }

    // -------------------------------------------------------------------- connectivity

    /** A node with no arc at all: it exists, and no flow can reach or leave it. */
    private void connectivity(Map<String, StagedNode> nodes, List<StagedLink> links,
            List<ImportDiagnostic> findings) {

        Set<String> connected = new HashSet<>();
        for (StagedLink link : links) {
            connected.add(RowValidator.key(link.source()));
            connected.add(RowValidator.key(link.target()));
        }
        for (StagedNode node : nodes.values()) {
            if (connected.contains(RowValidator.key(node.name()))) {
                continue;
            }
            findings.add(ImportDiagnostic.network(ImportSheet.NODES, ImportSeverity.WARNING,
                    ImportCheck.ORPHAN_NODE, node.name(),
                    ("'%s' has no links, so no flow can reach or leave it. It will appear on the "
                            + "canvas for you to connect.").formatted(node.name()))
                    .atLine(node.line()));
        }
    }

    // -------------------------------------------------------------------------- demand

    /**
     * Whether there is any demand to serve, and whether it is declared where the engine will look for
     * it.
     *
     * <p>Both warnings. A network with no demand yet is a normal intermediate state — the structure is
     * imported first and the demand added in the editor — and a demand row on a plant is a harmless
     * leftover that the engine ignores (demand belongs on customers and inventory on the supply
     * side). Saying so is worth it because neither shows up as an error anywhere later: the simulation
     * would simply report a fill rate of 1 over no demand at all, which reads like success.
     */
    private void demand(Map<String, StagedNode> nodes, List<StagedNodeProduct> nodeProducts,
            List<ImportDiagnostic> findings) {

        boolean anyCustomerDemand = false;
        for (StagedNodeProduct row : nodeProducts) {
            StagedNode node = nodes.get(RowValidator.key(row.node()));
            if (node == null) {
                continue;
            }
            double demand = row.demand().getValue() == null ? 0 : row.demand().getValue();
            if (demand <= 0) {
                continue;
            }
            if (node.type() == NodeType.CUSTOMER) {
                anyCustomerDemand = true;
            } else {
                findings.add(ImportDiagnostic.network(ImportSheet.NODE_PRODUCTS,
                        ImportSeverity.WARNING, ImportCheck.DEMAND_ON_NON_CUSTOMER,
                        row.node() + " / " + row.product(),
                        ("'%s' is a %s, and demand applies to CUSTOMER nodes — this row's "
                                + "demand will be ignored. Its inventory fields are still used.")
                                .formatted(node.name(), node.type()))
                        .atLine(row.line()));
            }
        }
        boolean hasCustomer = nodes.values().stream().anyMatch(n -> n.type() == NodeType.CUSTOMER);
        if (hasCustomer && !anyCustomerDemand) {
            findings.add(ImportDiagnostic.network(ImportSheet.NODE_PRODUCTS, ImportSeverity.WARNING,
                    ImportCheck.NO_DEMAND_DECLARED, null,
                    "No customer declares a positive demand, so a simulation over this network would "
                            + "have nothing to serve and every fill rate would read 1 over zero "
                            + "demand. Add demand in node_products, or in the editor afterwards."));
        }
    }
}
