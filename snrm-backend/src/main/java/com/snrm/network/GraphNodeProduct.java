package com.snrm.network;

/**
 * One product's parameters at one node, inside a {@link NetworkGraph} snapshot.
 *
 * <p>Both rates have been converted onto the network's period by {@code NetworkGraphFactory}:
 * {@code demandPerPeriod} is how much this node asks for in one step of the simulation loop, not the
 * number the user typed, and {@code holdingCostPerPeriod} is what one unit costs to carry for one
 * step. The declared value and its unit stay behind in the entity, where the API reads them.
 *
 * <p>Inventory and safety stock are not rates. They are stock levels — a quantity at an instant, not
 * a flow over time — so they cross the boundary unchanged and no conversion applies.
 * {@code unitValue} is likewise a price, not a rate.
 *
 * @param productId            the product
 * @param productName          its name, for metric and time-series labelling
 * @param demandPerPeriod      demand arising in one period; meaningful on CUSTOMER nodes
 * @param initialInventory     opening stock
 * @param safetyStock          target buffer, a Phase 2 redundancy lever
 * @param holdingCostPerPeriod cost of carrying one unit for one period
 * @param unitValue            what one unit is worth ({@code product.unit_value}). Carried
 *                             into the snapshot because it is what a unit of <em>unmet</em> demand
 *                             costs: the penalty arc has to be priced, and pricing it
 *                             from the product the customer wanted is the only reading under which
 *                             the minimum-cost flow's objective is the period's actual cost rather
 *                             than an arbitrary big-M. See {@code com.snrm.simulation.FlowAllocator}
 */
public record GraphNodeProduct(
        long productId,
        String productName,
        double demandPerPeriod,
        double initialInventory,
        double safetyStock,
        double holdingCostPerPeriod,
        double unitValue) {
}
