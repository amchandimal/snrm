package com.snrm.dataimport;

import com.snrm.common.DurationAmount;
import com.snrm.common.Rate;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import com.snrm.network.NodeType;

import java.util.List;

/**
 * A candidate network, read out of the files and not yet written anywhere.
 *
 * <p>The whole point of the two-stage validation is that this exists and the database rows do not:
 * the import is transactional, so nothing may be persisted until every check has passed, and
 * the network-level checks of stage 2 cannot run row by row — asking whether a customer has an inbound
 * path needs the entire graph. This is that graph, held in memory, in terms of the domain's own value
 * types rather than of strings.
 *
 * <p>Units are already resolved. Each unit-bearing field is a {@link DurationAmount} or {@link Rate}
 * carrying the unit the file stated, or the network's period unit where the file stated none — so
 * nothing downstream, including the resolution checks, has to know which of the two happened.
 *
 * <p>Every staged row keeps the line it came from. That is what lets a stage-2 finding about the graph
 * point back at a row of the file: "the link on line 8 points upstream" rather than "some link does".
 *
 * @param timeBase     the clock, from {@code network_meta}, the wizard's confirmation, or the defaults
 * @param declaredName the name the file gave the network, or null. The import request overrides it; a
 *                     file that carries one — an XML export always does — can be imported
 *                     without naming it again
 * @param nodes        staged node rows, in file order
 * @param links        staged link rows, in file order
 * @param products     staged product rows; empty when the sheet was absent
 * @param nodeProducts staged per-node, per-product rows
 * @param complete     false when stage 1 dropped at least one row, so stage 2 is looking at a partial
 *                     graph and its findings are provisional
 */
public record StagedNetwork(TimeBaseSpec timeBase, String declaredName, List<StagedNode> nodes,
        List<StagedLink> links, List<StagedProduct> products,
        List<StagedNodeProduct> nodeProducts, boolean complete) {

    public StagedNetwork {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        links = links == null ? List.of() : List.copyOf(links);
        products = products == null ? List.of() : List.copyOf(products);
        nodeProducts = nodeProducts == null ? List.of() : List.copyOf(nodeProducts);
    }

    /**
     * The network's time base.
     *
     * @param periodLength   length of one simulation period
     * @param horizonPeriods how many periods a run covers
     * @param roundingPolicy how a duration that does not divide evenly is resolved
     * @param declared       true when {@code network_meta} or the wizard supplied it; false when these
     *                       are the documented defaults, which the wizard must confirm
     */
    public record TimeBaseSpec(DurationAmount periodLength, int horizonPeriods,
            RoundingPolicy roundingPolicy, boolean declared) {

        /** 1 DAY, 52 periods, NEAREST — the defaults when {@code network_meta} is absent. */
        public static TimeBaseSpec defaults() {
            return new TimeBaseSpec(DurationAmount.of(1, TimeUnit.DAY), 52, RoundingPolicy.NEAREST,
                    false);
        }

        /** The unit an omitted {@code *_unit} column falls back to. */
        public TimeUnit periodUnit() {
            return periodLength.getUnit();
        }
    }

    /**
     * @param posX           editor canvas coordinate, or null when the file carried none — in which
     *                       case the editor auto-lays the network out on arrival
     * @param caption        the annotation of FR-30, or null when the cell was empty or absent
     * @param captionVisible already resolved: an absent {@code caption_visible} column, or an empty
     *                       cell, has become {@code true} here, because an omitted flag reads
     *                       as VISIBLE. Nothing downstream has to know which of the two
     *                       happened — the same rule the unit columns follow
     */
    public record StagedNode(int line, String name, NodeType type, Rate capacity,
            DurationAmount processingTime, double fixedCost, double varCost, double failureProb,
            String region, Double lat, Double lng, Double posX, Double posY, String caption,
            boolean captionVisible) {
    }

    /** Endpoints are still names here; they become node references only once the nodes are persisted. */
    public record StagedLink(int line, String source, String target, DurationAmount leadTime,
            Rate capacity, double unitCost, double failureProb, String caption,
            boolean captionVisible) {
    }

    public record StagedProduct(int line, String name, double unitValue) {
    }

    public record StagedNodeProduct(int line, String node, String product, Rate demand,
            double initialInventory, double safetyStock, Rate holdingCost) {
    }
}
