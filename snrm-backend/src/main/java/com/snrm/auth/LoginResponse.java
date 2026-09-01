package com.snrm.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A freshly issued bearer token.
 *
 * <p>Present it as {@code Authorization: Bearer <token>} on every other {@code /api/v1} call. The
 * expiry is returned in both forms on purpose: {@code expiresInSeconds} is what a client timer
 * needs, {@code expiresAt} is what a human reading the response needs.
 *
 * @param token            the signed JWT
 * @param tokenType        always {@code Bearer}
 * @param expiresInSeconds lifetime from now, in seconds
 * @param expiresAt        absolute expiry (UTC)
 * @param username         the authenticated user, echoed back
 */
@Schema(name = "LoginResponse", description = "A signed JWT bearer token and its expiry.")
public record LoginResponse(

        @Schema(description = "Signed JWT. Send as: Authorization: Bearer <token>",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzbnJtIn0.rL8k...")
        String token,

        @Schema(description = "Authentication scheme to use with the token.", example = "Bearer")
        String tokenType,

        @Schema(description = "Token lifetime from now, in seconds.", example = "28800")
        long expiresInSeconds,

        @Schema(description = "Absolute expiry instant (UTC).", example = "2026-07-25T22:14:07Z")
        Instant expiresAt,

        @Schema(description = "The authenticated research user.", example = "researcher")
        String username) {
}
