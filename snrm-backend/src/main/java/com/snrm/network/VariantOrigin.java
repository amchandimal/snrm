package com.snrm.network;

/**
 * Provenance of a {@link ConfigurationVariant} — how the variant came to exist
 * ({@code CONFIGURATION_VARIANT.generated_by}).
 *
 * <p>The column is an enum, and these two literals cover every path by which a variant can
 * come to exist today. Adding a third (an import-created variant, say) is a Flyway migration
 * plus a constant here.
 *
 * <p>Persisted as a MySQL {@code ENUM} — keep the literals in step with {@code V2__domain.sql}.
 */
public enum VariantOrigin {

    /**
     * Forked by the user, typically because the editor refused an edit to a network that had
     * already been simulated.
     */
    MANUAL,

    /**
     * Produced by the Phase 2 configuration search. Every evaluated candidate is persisted this
     * way, so the search itself yields analysable data about the configuration landscape.
     */
    SEARCH
}
