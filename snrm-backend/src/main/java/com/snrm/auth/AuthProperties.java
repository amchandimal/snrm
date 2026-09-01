package com.snrm.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The single research account and the JWT signing material, bound from {@code snrm.auth.*}
 * (single-user prototype, {@code POST /auth/login}, BCrypt credentials).
 *
 * <p>There is no user table in Phase 1 — the ER model has none, and
 * {@code project.owner_id} is a bare number for exactly that reason — so the credential lives in
 * configuration. Every value reaches the application through an environment-variable placeholder
 * in {@code application.properties}, so nothing secret is committed.
 *
 * <p>{@link #passwordHash()} and {@link #jwtSecret()} are deliberately allowed to be blank. When
 * they are, {@link ResearchUser} and {@link SecurityConfig} mint throwaway values at startup and
 * log them, so a fresh clone is testable before any environment setup. That is a development
 * convenience and both classes say so loudly in the log.
 *
 * @param username     login name of the research user
 * @param passwordHash BCrypt hash of that user's password ({@code $2a$} / {@code $2b$} /
 *                     {@code $2y$}), or blank to generate a throwaway password at startup
 * @param jwtSecret    HMAC-SHA256 signing key, at least 32 characters, or blank to generate a
 *                     random key at startup (tokens then do not survive a restart)
 * @param tokenTtl     lifetime of an issued token
 * @param issuer       {@code iss} claim of issued tokens
 * @param ownerId      value written to {@code project.owner_id}; constant while there is one
 *                     account, and the seam multi-user hardening widens
 */
@Validated
@ConfigurationProperties(prefix = "snrm.auth")
public record AuthProperties(

        @NotBlank(message = "snrm.auth.username must be set") String username,

        @DefaultValue("") String passwordHash,

        @DefaultValue("") String jwtSecret,

        @DefaultValue("PT8H") Duration tokenTtl,

        @DefaultValue("snrm") String issuer,

        @DefaultValue("1") long ownerId) {
}
