package com.snrm.auth;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Mints the bearer tokens {@code POST /api/v1/auth/login} returns.
 *
 * <p>Symmetric HS256, because there is one issuer and one verifier — the same application — and a
 * key pair would add operational weight with nothing to show for it. The signing key comes from
 * {@code snrm.auth.jwt-secret}; {@link SecurityConfig} owns its construction.
 *
 * <p>The {@link JwsHeader} is set explicitly. {@link JwtEncoder} defaults to RS256, which cannot be
 * produced from a symmetric key, so omitting the header would fail at the first login rather than
 * at startup.
 *
 * <p>Claims are the minimum the API needs: {@code sub} identifies the user, {@code uid} carries the
 * {@code project.owner_id} that {@link CurrentUser} scopes every query by, and {@code iss} /
 * {@code iat} / {@code exp} are the standard envelope the decoder validates. No authorities claim —
 * Phase 1 has no roles, and adding one now would invent a vocabulary nothing has settled yet.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    JwtService(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * Issues a token for the given user.
     *
     * @param username value of the {@code sub} claim
     * @return the token and its expiry, ready to return from the login endpoint
     */
    public LoginResponse issue(String username) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.tokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(username)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(CurrentUser.OWNER_ID_CLAIM, properties.ownerId())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new LoginResponse(token, "Bearer", properties.tokenTtl().toSeconds(), expiresAt, username);
    }
}
