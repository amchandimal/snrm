package com.snrm.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials presented to {@code POST /api/v1/auth/login}.
 */
@Schema(name = "LoginRequest", description = "Credentials of the single research user.")
public record LoginRequest(

        @Schema(description = "Login name, as configured in snrm.auth.username.",
                example = "researcher", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "username is required")
        @Size(max = 120, message = "username must be at most 120 characters")
        String username,

        @Schema(description = "Plain-text password. Checked against the configured BCrypt hash; "
                + "never stored or logged.",
                example = "change-me", requiredMode = Schema.RequiredMode.REQUIRED,
                format = "password")
        @NotBlank(message = "password is required")
        @Size(max = 200, message = "password must be at most 200 characters")
        String password) {
}
