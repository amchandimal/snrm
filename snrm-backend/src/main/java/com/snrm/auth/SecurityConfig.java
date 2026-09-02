package com.snrm.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

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
 * <p><strong>CORS is an allow-list, and it admits nothing unless configured.</strong>
 * {@code snrm.cors.allowed-origins} names the origins a browser may call this API from. It can
 * stay unset for the development setup, where {@code ng serve} proxies {@code /api} here and the
 * browser therefore sees one origin; it is set when the compiled bundle is hosted elsewhere and
 * calls this API by absolute URL. With no origins configured, no cross-origin request is answered
 * at all — the behaviour this class had before the property existed.
 *
 * <p>Two details of that configuration are load-bearing rather than boilerplate.
 * {@code Content-Disposition} is <em>exposed</em>, because every export and archive download reads
 * its filename off that header, and a header the browser will not surface is a header the client
 * cannot read. And credentials are <em>not</em> allowed: this API authenticates with a bearer
 * token the client attaches itself, so no cookie has to cross the origin boundary, and refusing
 * credentials means the allow-list is not the only thing standing between a hostile page and an
 * authenticated session.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthProperties.class, CorsProperties.class})
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** HS256 needs a key of at least 256 bits; anything shorter is rejected by Nimbus. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** How long a browser may reuse one preflight answer. */
    private static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

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
                // Picks up the CorsConfigurationSource bean below. It has to be registered here
                // rather than on the MVC side: a preflight OPTIONS carries no credential, so the
                // filter that answers it must run before the one that would reject it as anonymous.
                .cors(Customizer.withDefaults())
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

    /**
     * The cross-origin allow-list, or nothing at all when none is configured.
     *
     * <p>Returning {@code null} from the source is how this API declines to be a CORS API: the
     * filter writes no {@code Access-Control-Allow-Origin} header, and the browser refuses the
     * response on the caller's behalf. That is the right default for the proxied development
     * setup, where a cross-origin request would be a mistake rather than a configuration gap.
     *
     * <p>The methods are the ones the API actually answers on, and the request headers the ones a
     * client actually sends: the bearer token, the content type of a JSON body or a multipart
     * upload, and the accept header. A preflight is cached for an hour, so a browsing session
     * pays for it once per endpoint shape rather than once per request.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        if (properties.allowedOrigins().isEmpty()) {
            log.info("snrm.cors.allowed-origins is unset: no cross-origin browser request will be "
                    + "answered. Set it (environment variable SNRM_CORS_ORIGINS) if the frontend "
                    + "bundle is served from somewhere other than this application.");
            return request -> null;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        // Without this the filename of every export and archive download is unreadable from
        // script, and the client falls back to guessing one.
        configuration.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(PREFLIGHT_CACHE);

        log.info("CORS enabled for {}", properties.allowedOrigins());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
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
