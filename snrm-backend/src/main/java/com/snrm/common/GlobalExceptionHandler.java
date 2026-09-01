package com.snrm.common;

import com.snrm.network.NetworkImmutableException;
import com.snrm.scenario.EventHorizonException;
import com.snrm.simulation.UnresolvedEventException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps exceptions to RFC 7807 {@code application/problem+json} responses.
 * Spring MVC framework exceptions are covered by {@link ResponseEntityExceptionHandler};
 * domain exceptions get dedicated handlers here as the modules are implemented.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bean-validation failures on a request body (all inputs are bean-validated).
     *
     * <p>{@code fieldErrors} is keyed by property path, so the bulk canvas endpoints
     * report which element of the batch was rejected — {@code nodes[2].posX} rather than a single
     * opaque message for the whole request.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setProperty("code", "VALIDATION_FAILED");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getFieldErrors().forEach(f -> fieldErrors.putIfAbsent(f.getField(),
                f.getDefaultMessage() == null ? "invalid value" : f.getDefaultMessage()));
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request violates validation constraints.");
        problem.setProperty("violations", ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList());
        return problem;
    }

    /**
     * Network immutability. 409 because the request is well-formed and the caller may
     * legitimately retry after forking; the {@code code} and {@code networkId} members are what the
     * editor branches on to raise the fork prompt.
     */
    @ExceptionHandler(NetworkImmutableException.class)
    ProblemDetail handleNetworkImmutable(NetworkImmutableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Network is immutable");
        problem.setProperty("code", ex.code());
        problem.setProperty("networkId", ex.getNetworkId());
        return problem;
    }

    /**
     * The horizon rule, as a refusal: a disruption event whose window ends after the network's
     * horizon.
     *
     * <p>422 like any other {@link DomainException}, but with the four numbers attached, because
     * "extend the horizon" is only actionable next to how far the event overruns it. The scenario
     * builder shows them beside the bar it just refused to move; without them it would
     * have to re-derive the arithmetic client-side to say anything more useful than the message.
     */
    @ExceptionHandler(EventHorizonException.class)
    ProblemDetail handleEventHorizon(EventHorizonException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage());
        problem.setTitle("Event ends after the horizon");
        problem.setProperty("code", ex.code());
        problem.setProperty("networkId", ex.getNetworkId());
        problem.setProperty("startPeriod", ex.getStartPeriod());
        problem.setProperty("windowPeriods", ex.getWindowPeriods());
        problem.setProperty("endPeriod", ex.getEndPeriod());
        problem.setProperty("horizonPeriods", ex.getHorizonPeriods());
        return problem;
    }

    /**
     * A scenario event, at run time, that names nothing in the network being simulated.
     *
     * <p>422 with the offending event ids attached, so the timeline editor can highlight the bars
     * that need re-targeting rather than making the researcher find them among fifty.
     * This is the cross-variant case {@code DisruptionScenarioService} says it cannot answer at
     * write time, answered where the (network, scenario) pair is finally known.
     */
    @ExceptionHandler(UnresolvedEventException.class)
    ProblemDetail handleUnresolvedEvent(UnresolvedEventException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage());
        problem.setTitle("Disruption event targets nothing in this network");
        problem.setProperty("code", ex.code());
        problem.setProperty("networkId", ex.getNetworkId());
        problem.setProperty("scenarioId", ex.getScenarioId());
        problem.setProperty("eventIds", ex.getEventIds());
        return problem;
    }

    /**
     * The bounded job queue refused a submission.
     *
     * <p>429 rather than 503: the request is well-formed and the remedy is to send it again shortly,
     * which is what that status means. {@code Retry-After} carries a conservative hint in seconds —
     * a simulation job is not a request-scale unit of work, and a client polling every second would
     * only add load to a machine that is already saturated.
     */
    @ExceptionHandler(JobQueueFullException.class)
    ResponseEntity<ProblemDetail> handleJobQueueFull(JobQueueFullException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                ex.getMessage());
        problem.setTitle("Job queue is full");
        problem.setProperty("code", ex.code());
        problem.setProperty("workers", ex.getWorkers());
        problem.setProperty("queueCapacity", ex.getQueueCapacity());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(JOB_RETRY_AFTER_SECONDS))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** Seconds a client should wait before resubmitting a refused job. */
    private static final int JOB_RETRY_AFTER_SECONDS = 30;

    /**
     * A rule broken by what is already stored rather than by the request — a duplicate link, a name
     * already in use. 409 rather than the 422 of a plain {@link DomainException}, because the
     * caller has a remedy: change the name, or edit the link that exists.
     */
    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        problem.setProperty("code", ex.code());
        return problem;
    }

    /** Any other domain-rule violation; more specific handlers win over this one. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleNotFound(EntityNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not found");
        problem.setProperty("code", "NOT_FOUND");
        return problem;
    }

    /**
     * A request the services accepted but the schema did not: the unique keys and {@code CHECK}
     * constraints of {@code V2__domain.sql}.
     *
     * <p>Reaching here means a service-level check is missing or lost a race, so the cause is
     * logged in full while the response stays deliberately vague — a constraint name is an internal
     * detail, and the SQL text can carry the row's values.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint rejected a request that passed service-level validation", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with data that already exists, or violates a schema constraint.");
        problem.setTitle("Conflict");
        problem.setProperty("code", "CONSTRAINT_VIOLATION");
        return problem;
    }

    /**
     * A failed login. Rejections raised by the filter chain never reach this class — they
     * are answered by {@code auth/ProblemAuthenticationEntryPoint} — but the credential check in
     * {@code AuthController} is ordinary controller code and lands here.
     *
     * <p>The detail does not say which half was wrong: confirming that a username exists narrows
     * the next guess and helps no legitimate caller.
     */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Invalid username or password.");
        problem.setTitle("Unauthenticated");
        problem.setProperty("code", "INVALID_CREDENTIALS");
        return problem;
    }

    /**
     * An argument a service refused as malformed. Distinct from a bean-validation failure: this is
     * a check the annotations cannot express, such as one id having to belong to another resource.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad request");
        problem.setProperty("code", "BAD_REQUEST");
        return problem;
    }

    /**
     * A multipart request the container refused to parse.
     *
     * <p>400, because it is the request that is wrong and the caller can act on it — and because the
     * alternative is what this handler was written for: the exception is raised by
     * {@code DispatcherServlet.checkMultipart} before any handler is resolved, so without this it fell
     * through to {@link #handleUnexpected} and the import wizard showed "an unexpected error occurred"
     * for something as ordinary as attaching too many files.
     *
     * <p>{@code MaxUploadSizeExceededException} is <em>not</em> handled here: it is a subclass, but
     * {@link ResponseEntityExceptionHandler} already maps it to 413, which is the more precise answer.
     * Declaring it again in this class would be an ambiguous mapping and would fail at startup.
     */
    @ExceptionHandler(MultipartException.class)
    ProblemDetail handleMultipart(MultipartException ex) {
        log.warn("Rejected a multipart request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                ("The upload could not be read as a multipart request. Usual causes: more parts than "
                        + "the server accepts (%d, files and form fields together), or a malformed "
                        + "request body. Underlying error: %s")
                        .formatted(UploadLimitsConfig.MAX_PARTS, ex.getMessage()));
        problem.setTitle("Malformed upload");
        problem.setProperty("code", "MULTIPART_NOT_READABLE");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
    }
}
