package com.snrm.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * The browser origins allowed to call this API, bound from {@code snrm.cors.*}.
 *
 * <p>Empty by default in spirit: when the frontend is served by {@code ng serve} its dev server
 * proxies {@code /api} to this application, so the browser sees one origin and no cross-origin
 * request is ever made. The list matters only when the compiled bundle is hosted somewhere else —
 * a static host, a CDN, GitHub Pages — and points at this API by absolute URL.
 *
 * <p>Origins are matched <em>exactly</em>, scheme and host and port, which is what the CORS
 * specification means by an origin. {@code https://example.github.io} does not admit
 * {@code http://example.github.io}, and a path on the end is not part of an origin at all: a page
 * at {@code https://example.github.io/app/index.html} sends {@code https://example.github.io}.
 * No wildcard is accepted, because the value that would make one convenient — {@code *} — is the
 * value that makes the allow-list meaningless.
 *
 * @param allowedOrigins origins permitted to make a cross-origin call, or empty to permit none
 */
@Validated
@ConfigurationProperties(prefix = "snrm.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        // A missing property binds to null; an empty entry is what a trailing comma in the
        // environment variable leaves behind. Neither should reach the CORS configuration.
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
    }
}
