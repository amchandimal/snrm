package com.snrm.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's {@code project.owner_id} from the bearer token.
 *
 * <p>Phase 1 has one account, so this always answers the same number. It exists anyway because
 * {@code project.owner_id} is {@code NOT NULL} and something has to supply it, and because it is
 * the single place multi-user hardening has to change: once there are real users, the claim stops
 * being a constant and every service that already scopes its queries by this value keeps working
 * unchanged.
 *
 * <p>Reads {@link org.springframework.security.core.context.SecurityContextHolder} rather than
 * taking an {@link Authentication} parameter so services can call it without every controller
 * signature growing a principal argument. The filter chain guarantees an authenticated JWT on every
 * path that reaches a service; the configured fallback covers the ones that do not, such as a
 * scheduled job or a test slice with security disabled.
 */
@Component
public class CurrentUser {

    /** Private claim carrying {@code project.owner_id}. Part of the token contract. */
    public static final String OWNER_ID_CLAIM = "uid";

    private final long fallbackOwnerId;

    CurrentUser(AuthProperties properties) {
        this.fallbackOwnerId = properties.ownerId();
    }

    /** The {@code project.owner_id} rows created by this request belong to. */
    public long ownerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getClaim(OWNER_ID_CLAIM) instanceof Number ownerId) {
            return ownerId.longValue();
        }
        return fallbackOwnerId;
    }
}
