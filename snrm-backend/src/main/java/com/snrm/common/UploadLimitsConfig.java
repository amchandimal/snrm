package com.snrm.common;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How many parts one multipart request may carry.
 *
 * <p><strong>Why this exists.</strong> Tomcat 10.1.42 introduced {@code maxPartCount}, a limit on the
 * number of parts in a multipart request, defaulting to <strong>10 — form fields included</strong>. A
 * network import sends five files plus the project, the name, the baseline flag, the dry-run
 * flag, the column mapping and the four fields of the confirmed time base: comfortably past ten. Tomcat
 * rejects the request in {@code parseParts}, before Spring has resolved a handler, so nothing in the
 * importer gets a say.
 *
 * <p>The size properties in {@code application.properties} do not cover it: a part <em>count</em> and a
 * byte <em>size</em> are separate limits, and the request that fails here is a few kilobytes.
 * Spring Boot 3.3 has no property for the count either — hence a connector customizer.
 *
 * <p><strong>Why 64 and not "unlimited".</strong> The limit exists to bound how many parts a single
 * request can make the container allocate, and turning it off entirely would give that up for no gain:
 * the canonical schema has five sheets and the import sends nine metadata fields, so a legitimate
 * request is around fifteen parts even with a few extra files attached. Sixty-four leaves generous room
 * for the schema to grow while keeping the ceiling meaningful. The real bound on an upload is
 * {@code spring.servlet.multipart.max-request-size}, which stays at 32 MB.
 */
@Configuration
public class UploadLimitsConfig {

    /**
     * Parts allowed in one multipart request, files and form fields together.
     *
     * @see #partCountLimit()
     */
    static final int MAX_PARTS = 64;

    /**
     * Raises Tomcat's {@code maxPartCount} from its default of 10.
     *
     * <p>A connector customizer rather than a property because Spring Boot 3.3 does not expose this one
     * ({@code server.tomcat.max-part-count} arrives later). If the embedded container is ever swapped
     * for Jetty or Undertow this bean simply stops applying — neither imposes a comparable limit — and
     * the import keeps working.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> partCountLimit() {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setMaxPartCount(MAX_PARTS));
    }
}
