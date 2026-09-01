/**
 * Network, Node, Link, Product and NodeProduct entities, the NetworkGraph snapshot builder, and the
 * REST resources over the network aggregate.
 *
 * <p>{@link com.snrm.network.ConfigurationVariant} lives here too, although a separate
 * {@code configuration} module is planned: that module is the Phase 2 engine and must depend
 * only on the {@code NetworkGraph} snapshot, so a JPA entity cannot live there. The variant is also
 * created on the Phase 1 path — an editor fork — which this module owns.
 *
 * <p>{@link com.snrm.network.NetworkMutationGuard} enforces the freeze: the <em>structure</em> of a
 * network referenced by a simulation run is frozen, and structural edits must fork a variant
 * instead. Every write path to that structure goes through it —
 * {@link com.snrm.network.NetworkService}'s time base and delete,
 * {@link com.snrm.network.NodeService}, {@link com.snrm.network.LinkService} and
 * {@link com.snrm.network.ProductService}'s node-product writes all open with it, and anything added
 * later must too. The single exemption is {@link com.snrm.network.NetworkService#update}: a rename
 * and the baseline flag are not structure, are an input to no metric, and are not refused on a
 * frozen network (FR-29). The guard's own class note carries the
 * scope; that method carries the argument.
 *
 * <p>The services return DTOs and map inside their transaction. That is not stylistic:
 * {@code spring.jpa.open-in-view=false}, so a lazy association touched after a service returns
 * would throw — and JPA entities must never reach a controller signature in any
 * case. Mapping is MapStruct's, generated at compile time from the {@code *Mapper} interfaces.
 *
 * <p>{@link com.snrm.network.NetworkLookup} is the single place a path id becomes an entity, and
 * the single place ownership is checked. Widening that check is what multi-user hardening will
 * amount to.
 *
 * <p>{@link com.snrm.network.NetworkGraphFactory} is the boundary of the unit system:
 * it is the only code that converts a declared duration or rate onto the network's period, and
 * the {@link com.snrm.network.NetworkGraph} it produces exposes nothing but period counts and
 * per-period quantities. {@link com.snrm.network.TimeValidationService} answers the other half of
 * that question — what the conversion costs — as the findings.
 */
package com.snrm.network;
