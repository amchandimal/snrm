/**
 * Phase 2 Configuration Engine: ConfigurationStrategy SPI, GA searchers, Pareto assembly.
 *
 * <p>Depends only on the immutable {@code NetworkGraph} snapshot and the Phase 1 metric and
 * simulation engines — never on JPA entities or web classes. Searches run
 * through the async {@code JobService}.
 *
 * <p>The {@code CONFIGURATION_VARIANT} table is therefore mapped in {@code network}, as
 * {@link com.snrm.network.ConfigurationVariant}, not here: putting a JPA entity in this package
 * would break the isolation above on day one. A search persists each evaluated candidate through
 * that entity, tagged {@link com.snrm.network.VariantOrigin#SEARCH}.
 */
package com.snrm.configuration;
