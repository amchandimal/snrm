package com.snrm.network;

import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body of {@code PUT /api/v1/nodes/{nodeId}/products/{productId}} (FR-01).
 *
 * <p>The two ids are in the path, not here: {@code node_product} is keyed by the natural
 * {@code (node_id, product_id)} pair, so the URL already names the row completely and a PUT is an
 * upsert — it creates the row on first call and replaces it afterwards.
 *
 * <p>All four values default to 0, matching the column defaults of {@code V2__domain.sql}. For the
 * two rates that means zero over the network's period unit — neither column is nullable,
 * so there is no unconstrained form to fall back to, and a {@code null} value inside a rate that
 * <em>is</em> sent is read as zero for the same reason.
 *
 * <p>Ranges mirror {@code ck_node_product_demand} and {@code ck_node_product_inventory}. Holding
 * cost is unconstrained in the schema, but a rate on the wire is non-negative by construction
 * ({@link RateDto}), which tightens it to ≥ 0 here — a negative carrying cost is not a modelled
 * concept.
 */
@Schema(name = "NodeProductRequest",
        description = "Demand, inventory and holding cost for one product at one node.")
public record NodeProductRequest(

        @Schema(description = "Demand and the unit it is measured over, e.g. "
                + "`{\"value\": 120, \"timeUnit\": \"WEEK\"}`. Meaningful on CUSTOMER nodes. "
                + "Omit for zero.", nullable = true)
        @Valid
        RateDto demand,

        @Schema(description = "Opening stock. A stock level, not a flow — no unit "
                + "applies. Defaults to 0.", example = "200.0", defaultValue = "0", minimum = "0")
        @PositiveOrZero(message = "initialInventory must not be negative")
        double initialInventory,

        @Schema(description = "Target buffer. A stock level, not a flow. Defaults to 0.",
                example = "50.0", defaultValue = "0", minimum = "0")
        @PositiveOrZero(message = "safetyStock must not be negative")
        double safetyStock,

        @Schema(description = "Cost of carrying one unit, and the unit of time it is charged over, "
                + "e.g. `{\"value\": 0.4, \"timeUnit\": \"DAY\"}`. Omit for zero.", nullable = true)
        @Valid
        RateDto holdingCost) {
}
