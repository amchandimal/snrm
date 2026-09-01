package com.snrm.scenario;

/**
 * What a {@link DisruptionEvent} strikes ({@code DISRUPTION_EVENT.target_type}).
 *
 * <p>Persisted as a MySQL {@code ENUM} — keep the literals in step with {@code V2__domain.sql}.
 */
public enum DisruptionTargetType {

    /** A single node, identified by {@code target_id}. */
    NODE,

    /** A single link, identified by {@code target_id}. */
    LINK,

    /**
     * Every node carrying a given region tag, which is how correlated geographic disruptions are
     * expressed.
     *
     * <p>The region is named by {@code target_region}, not by {@code target_id}: the ER
     * model types the target as a {@code bigint}, which cannot hold a tag, and {@code node.region}
     * is a {@code VARCHAR(60)} string. V2 followed the ER model as written and left this constant
     * unusable — an event could say it struck a region but not which one — and
     * {@code V5__event_region_target.sql} closed it with a matching {@code VARCHAR(60)} column.
     * That migration's header is the full argument, including why a region lookup table was not
     * the answer.
     *
     * <p>The set a region resolves to is a property of a <em>network</em>, not of the scenario: the
     * same tag names different nodes in two configuration variants, which is the point of scoping
     * scenarios to the project. {@code GET /api/v1/networks/{id}/region-nodes} answers it, and is
     * what the scenario builder previews before an event is saved.
     */
    REGION
}
