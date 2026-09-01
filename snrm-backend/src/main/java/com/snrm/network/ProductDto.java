package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A product as the API returns it ({@code PRODUCT}, DTO boundary).
 *
 * @param id        surrogate key
 * @param projectId owning project — products are project-scoped, not network-scoped
 * @param name      unique within the project
 * @param unitValue value of one unit; weights unserved demand in the economic metrics
 * @param createdAt audit timestamp
 * @param updatedAt audit timestamp
 */
@Schema(name = "Product",
        description = "A product flowing through the network. Project-scoped, so every "
                + "configuration variant of a project shares one catalogue.")
public record ProductDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Owning project.", example = "1")
        Long projectId,

        @Schema(description = "Product name; unique within the project.", example = "Gearbox")
        String name,

        @Schema(description = "Value of one unit. Weights unserved demand when service loss is "
                + "expressed in money.", example = "250.0")
        double unitValue,

        @Schema(description = "When the product was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the product last changed (UTC).")
        Instant updatedAt) {
}
