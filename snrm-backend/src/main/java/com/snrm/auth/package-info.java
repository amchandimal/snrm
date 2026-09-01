/**
 * JWT authentication.
 *
 * <p>Phase 1 authenticates one research account, so there is no user table and no
 * {@code UserDetailsService}: the credential is configuration ({@link com.snrm.auth.AuthProperties}),
 * the store is {@link com.snrm.auth.ResearchUser}, and {@link com.snrm.auth.AuthController} trades a
 * verified password for a token minted by {@link com.snrm.auth.JwtService}.
 *
 * <p>{@link com.snrm.auth.SecurityConfig} makes every other {@code /api/v1} path require that token
 * and answers rejections as RFC 7807 {@code problem+json}, matching what the controllers return
 * {@link com.snrm.auth.CurrentUser} is the single place a request turns into a
 * {@code project.owner_id}, and the one thing multi-user hardening has to revisit.
 */
package com.snrm.auth;
