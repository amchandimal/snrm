package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/projects/{projectId}/networks} and
 * {@code PUT /api/v1/networks/{networkId}}.
 *
 * <p>{@code version} is not a field. It is assigned by the server — the baseline is 1 and each
 * clone takes the next number under the same name — so a client cannot mint a version
 * that collides with {@code uq_network} or reorders a variant history.
 */
@Schema(name = "NetworkRequest", description = "Fields of a network the client supplies.")
public record NetworkRequest(

        @Schema(description = "Network name.", example = "Baseline",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160)
        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @Schema(description = "Mark this as the project's baseline — the network the comparison "
                + "view measures the others against. A project may have at most one; "
                + "defaults to false.",
                example = "true", defaultValue = "false")
        boolean baseline) {
}
