package com.snrm.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The document springdoc serves at {@code /v3/api-docs} and Swagger UI renders.
 *
 * <p>Declares the bearer scheme once and applies it to every operation, so each controller only has
 * to describe what it does rather than restate how it is secured. {@code POST /api/v1/auth/login}
 * opts out with an empty {@code @SecurityRequirements}, which is the only exception.
 */
@Configuration
public class OpenApiConfig {

    /** Name of the security scheme; referenced by operations that opt out of it. */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI snrmOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SNRM API")
                        .version("v1")
                        .description("""
                                Supply Network Resilience Modelling tool — Phase 1 REST API.

                                **Authenticating in this UI:** call `POST /api/v1/auth/login`, copy the \
                                `token` from the response, then press **Authorize** above and paste it. \
                                Every other endpoint requires it.

                                All errors are RFC 7807 `application/problem+json`. Domain rules add a \
                                machine-readable `code` member — `NETWORK_IMMUTABLE`, `LINK_SELF_LOOP`, \
                                `LINK_DUPLICATE`, `LINK_CROSS_NETWORK`, `DUPLICATE_NAME` — which clients \
                                branch on instead of parsing messages."""))
                .servers(List.of(new Server().url("/").description("This application")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT issued by POST /api/v1/auth/login.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
