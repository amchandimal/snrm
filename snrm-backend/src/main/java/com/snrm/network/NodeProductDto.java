package com.snrm.network;

import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Per-node, per-product parameters as the API returns them ({@code NODE_PRODUCT}).
 *
 * <p>{@link #productName} is denormalised into the response on purpose: the property panel shows a
 * node's products as a list and would otherwise have to fetch the catalogue to label four numbers.
 *
 * <p>{@link #demand} and {@link #holdingCost} are rates over their own unit, not per-period
 * quantities: a demand of 120 a week reads back as 120 a week whether the network steps in
 * days or in weeks. {@link #initialInventory} and {@link #safetyStock} stay bare numbers — a stock
 * level is a quantity at an instant and has no time dimension to state.
 *
 * @param nodeId           the node
 * @param productId        the product
 * @param productName      the product's name, for display
 * @param demand           demand over its own unit; meaningful on CUSTOMER nodes
 * @param initialInventory opening stock; supply-side nodes
 * @param safetyStock      target buffer; a Phase 2 redundancy lever
 * @param holdingCost      cost of carrying one unit for one of its own unit
 * @param createdAt        audit timestamp
 * @param updatedAt        audit timestamp
 */
@Schema(name = "NodeProduct",
        description = "Demand, inventory and holding cost for one product at one node.")
public record NodeProductDto(

        @Schema(description = "The node.", example = "3")
        Long nodeId,

        @Schema(description = "The product.", example = "1")
        Long productId,

        @Schema(description = "The product's name, for display.", example = "Gearbox")
        String productName,

        @Schema(description = "Demand and the unit it is measured over. Meaningful on CUSTOMER "
                + "nodes.")
        RateDto demand,

        @Schema(description = "Opening stock. Inventory acts as an additional supply source in the "
                + "period after it is stocked.", example = "200.0")
        double initialInventory,

        @Schema(description = "Target buffer. One of the Phase 2 redundancy levers.",
                example = "50.0")
        double safetyStock,

        @Schema(description = "Cost of carrying one unit, and the unit of time it is charged over.")
        RateDto holdingCost,

        @Schema(description = "When the row was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the row last changed (UTC).")
        Instant updatedAt) {
}
