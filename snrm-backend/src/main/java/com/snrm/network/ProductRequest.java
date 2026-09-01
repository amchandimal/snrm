package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/projects/{projectId}/products} and
 * {@code PUT /api/v1/products/{productId}}.
 */
@Schema(name = "ProductRequest", description = "Fields of a product the client supplies.")
public record ProductRequest(

        @Schema(description = "Product name; unique within the project.", example = "Gearbox",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 120)
        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Schema(description = "Value of one unit. Defaults to 0.", example = "250.0",
                defaultValue = "0", minimum = "0")
        @PositiveOrZero(message = "unitValue must not be negative")
        double unitValue) {
}
