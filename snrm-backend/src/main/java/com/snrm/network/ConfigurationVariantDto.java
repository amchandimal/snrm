package com.snrm.network;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * The provenance record of a forked network ({@code CONFIGURATION_VARIANT}).
 *
 * <p>Carries the whole cloned {@link #network} rather than just its id: a client that has just
 * asked for a fork always needs the copy next, and the round trip would be certain.
 *
 * @param id            surrogate key
 * @param projectId     owning project
 * @param baseNetworkId the network this was forked from
 * @param network       the copy, materialising this variant (ER 1:1)
 * @param generatedBy   {@code MANUAL} for an editor fork, {@code SEARCH} for a Phase 2 candidate
 * @param leverChanges  structured diff from the base network, or null
 * @param createdAt     audit timestamp
 * @param updatedAt     audit timestamp
 */
@Schema(name = "ConfigurationVariant",
        description = "Links a derived network back to the one it was forked from, with the lever "
                + "diff between them.")
public record ConfigurationVariantDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Owning project.", example = "1")
        Long projectId,

        @Schema(description = "The network this variant was forked from.", example = "1")
        Long baseNetworkId,

        @Schema(description = "The editable copy this variant materialises as.")
        NetworkDto network,

        @Schema(description = "How the variant came to exist. Clones requested through the API are "
                + "always MANUAL; SEARCH marks a candidate persisted by the Phase 2 configuration "
                + "engine.", example = "MANUAL")
        VariantOrigin generatedBy,

        @Schema(description = "Structured diff from the base network, as supplied when cloning.",
                nullable = true)
        JsonNode leverChanges,

        @Schema(description = "When the variant was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the variant last changed (UTC).")
        Instant updatedAt) {
}
