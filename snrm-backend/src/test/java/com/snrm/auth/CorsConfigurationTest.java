package com.snrm.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-origin allow-list, as the browser will see it.
 *
 * <p>Needs no Spring context and no database: {@link SecurityConfig#corsConfigurationSource} is a
 * pure function of {@link CorsProperties}, so the configuration a request would actually be
 * matched against can be built and read directly. That matters because CORS is a rule enforced in
 * a browser, somewhere no test here runs — the header set is the only thing this side controls,
 * and it is worth pinning.
 */
@DisplayName("CORS allow-list")
class CorsConfigurationTest {

    private static final String PAGES = "https://amchandimal.github.io";

    private static CorsConfiguration configurationFor(String... origins) {
        CorsConfigurationSource source =
                new SecurityConfig().corsConfigurationSource(new CorsProperties(Arrays.asList(origins)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        return source.getCorsConfiguration(request);
    }

    @Nested
    @DisplayName("with an origin configured")
    class Configured {

        private final CorsConfiguration configuration = configurationFor(PAGES);

        @Test
        @DisplayName("admits exactly the configured origin")
        void admitsTheConfiguredOrigin() {
            assertThat(configuration.checkOrigin(PAGES)).isEqualTo(PAGES);
        }

        @Test
        @DisplayName("an origin differing only in scheme, host or port is a different origin")
        void refusesEveryNeighbouringOrigin() {
            assertThat(configuration.checkOrigin("http://amchandimal.github.io")).isNull();
            assertThat(configuration.checkOrigin("https://amchandimal.github.io.evil.test")).isNull();
            assertThat(configuration.checkOrigin("https://amchandimal.github.io:8443")).isNull();
            assertThat(configuration.checkOrigin("https://evil.test")).isNull();
        }

        @Test
        @DisplayName("exposes Content-Disposition, which every download reads its filename from")
        void exposesTheFilenameHeader() {
            assertThat(configuration.getExposedHeaders()).contains(HttpHeaders.CONTENT_DISPOSITION);
        }

        @Test
        @DisplayName("allows the bearer token through, since that is how the API authenticates")
        void allowsTheAuthorizationHeader() {
            assertThat(configuration.checkHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE)))
                    .containsExactly(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE);
        }

        @Test
        @DisplayName("answers every method the API offers, preflight included")
        void allowsEveryMethodTheApiOffers() {
            assertThat(configuration.getAllowedMethods())
                    .containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        }

        @Test
        @DisplayName("refuses credentials: a bearer token needs no cookie to cross the boundary")
        void refusesCredentials() {
            assertThat(configuration.getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
        }
    }

    @Nested
    @DisplayName("with nothing configured")
    class Unconfigured {

        @Test
        @DisplayName("answers no cross-origin request at all — the proxied development default")
        void answersNothing() {
            assertThat(configurationFor()).isNull();
        }

        @Test
        @DisplayName("a blank or whitespace entry is not an origin and does not open the list")
        void ignoresBlankEntries() {
            assertThat(configurationFor("", "   ")).isNull();
        }
    }

    @Test
    @DisplayName("a trailing comma in the environment variable does not become an empty origin")
    void trimsWhatTheEnvironmentLeavesBehind() {
        CorsConfiguration configuration = configurationFor(PAGES, "  ", " https://second.test ");

        assertThat(configuration.getAllowedOrigins()).containsExactly(PAGES, "https://second.test");
    }
}
