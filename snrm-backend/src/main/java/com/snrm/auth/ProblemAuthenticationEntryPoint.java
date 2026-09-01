package com.snrm.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Answers a missing, malformed or expired bearer token with RFC 7807 {@code problem+json}
 *
 * <p>{@code common/GlobalExceptionHandler} cannot cover this: authentication fails inside the
 * filter chain, before any controller — and therefore before {@code @RestControllerAdvice} — is
 * reached. Without this component the caller gets an empty 401 body, which is exactly the case a
 * client is least able to diagnose.
 *
 * <p>The {@code WWW-Authenticate} header is still set, since a 401 without one is not a valid
 * challenge.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Problem-detail {@code code} clients branch on to trigger a re-login. */
    public static final String CODE = "UNAUTHENTICATED";

    private final ObjectMapper objectMapper;

    ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "A valid bearer token is required. Obtain one from POST "
                        + AuthController.LOGIN_PATH + " and send it as: Authorization: Bearer <token>");
        problem.setTitle("Unauthenticated");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", CODE);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("WWW-Authenticate", "Bearer");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
