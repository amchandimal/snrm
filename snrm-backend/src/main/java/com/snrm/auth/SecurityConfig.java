package com.snrm.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Stateless JWT security for the whole API.
 *
 * <p>Everything under {@code /api/v1} requires a bearer token except {@code POST /api/v1/auth/login},
 * which is how a token is obtained in the first place. The operational endpoints (actuator
 * health/info) and the OpenAPI UI stay open so the contract can be read and the service probed
 * without a credential — the UI is a development aid, and
 * {@code application-prod.properties} switches it off entirely.
 *
 * <p>Sessions are disabled and CSRF with them: there is no cookie to forge, and the SPA holds the
 * token itself.
 *
 * <p><strong>Signing key.</strong> HS256 over {@code snrm.auth.jwt-secret}. Symmetric, because one
 * application both issues and verifies; a key pair would add operational weight with nothing to
 * show for it. When the property is blank a random key is generated at startup so a fresh clone
 * works without setup — every token issued before a restart then stops verifying after it, which
 * the log says plainly.
 *
 * <p><strong>CORS is deliberately absent.</strong> It would be restricted to the SPA origin, but the
 * Angular client does not call this API yet and guessing its origin here would be configuration
 * nobody has validated. Add it with the first cross-origin request, not before.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** HS256 needs a key of at least 256 bits; anything shorter is rejected by Nimbus. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** Paths reachable without a token. Everything else is authenticated. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, AuthController.LOGIN_PATH).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(authenticationEntryPoint))
                // A rejection has to be RFC 7807 like every other error the API returns;
                // the defaults answer with an empty body and a WWW-Authenticate header only.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** BCrypt at its default strength. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The HMAC key both halves of the flow share.
     *
     * @throws IllegalStateException if a secret is configured but too short to sign HS256 with —
     *                               failing at startup beats failing at the first login
     */
    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        if (!StringUtils.hasText(properties.jwtSecret())) {
            byte[] generated = new byte[MINIMUM_SECRET_BYTES];
            new SecureRandom().nextBytes(generated);
            log.warn("""

                            ================================================================
                            No snrm.auth.jwt-secret configured — signing tokens with a random
                            key generated for this run. Every token stops verifying when the
                            application restarts. Set SNRM_JWT_SECRET (>= {} characters) for
                            a stable key, for example:
                                {}
                            ================================================================""",
                    MINIMUM_SECRET_BYTES, HexFormat.of().formatHex(generated));
            return new SecretKeySpec(generated, "HmacSHA256");
        }

        byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException((
                    "snrm.auth.jwt-secret (environment variable SNRM_JWT_SECRET) is %d bytes; "
                            + "HS256 requires at least %d. Use a longer random string.")
                    .formatted(secret.length, MINIMUM_SECRET_BYTES));
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSigningKey));
    }

    /**
     * Verifies signature, {@code exp} and {@code nbf}. The algorithm is pinned so a token naming a
     * different one is rejected rather than negotiated.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
