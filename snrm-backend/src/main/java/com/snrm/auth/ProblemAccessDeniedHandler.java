package com.snrm.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Answers an authenticated-but-forbidden request with RFC 7807 {@code problem+json}.
 *
 * <p>Phase 1 has no roles, so nothing currently produces a 403. The handler is registered
 * anyway for the same reason {@link ProblemAuthenticationEntryPoint} is: a denial raised inside the
 * filter chain never reaches {@code common/GlobalExceptionHandler}, and the default answer is an
 * empty body. When project-level ownership checks become real authorities, this is
 * already the shape of their rejection.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    /** Problem-detail {@code code} for a denied request. */
    public static final String CODE = "ACCESS_DENIED";

    private final ObjectMapper objectMapper;

    ProblemAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "The authenticated user may not access this resource.");
        problem.setTitle("Access denied");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", CODE);

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
