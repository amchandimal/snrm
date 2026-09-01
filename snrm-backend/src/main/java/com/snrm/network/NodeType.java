package com.snrm.network;

/**
 * Echelon a node occupies in the supply network ({@code NODE.type}, import schema).
 *
 * <p>The order of the constants is the canonical upstream-to-downstream direction and is what the
 * editor's echelon-direction warnings are checked against: a link into a
 * {@link #SUPPLIER} or out of a {@link #CUSTOMER} is blocked, lateral links such as
 * {@code DC -> DC} are legal but flagged.
 *
 * <p>Persisted as a MySQL {@code ENUM} — the literals must stay in step with the column definition
 * in {@code V2__domain.sql}; changing either one is a Flyway migration, never an edit.
 */
public enum NodeType {

    /** Upstream source of material; has no inbound links. */
    SUPPLIER,

    /** Production site; transforms inbound material into outbound product. */
    PLANT,

    /** Distribution centre; stocks and forwards, may hold inventory and transship laterally. */
    DC,

    /** Demand sink; carries {@code demand_per_period} per product and has no outbound links. */
    CUSTOMER
}
