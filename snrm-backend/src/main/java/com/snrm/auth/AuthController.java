package com.snrm.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Obtains a bearer token for the single research user ({@code POST /auth/login}).
 *
 * <p>The only endpoint in the API that does not require a token — {@link SecurityConfig} permits
 * exactly this method and path, and the operation carries an empty {@code @SecurityRequirements} so
 * Swagger UI does not ask for one either.
 *
 * <p>A failed login returns 401 with no hint as to which half was wrong: telling a caller that the
 * username exists narrows their next guess for no benefit to a legitimate user.
 */
@Tag(name = "Authentication",
        description = "Obtain the JWT bearer token every other /api/v1 endpoint requires.")
@RestController
public class AuthController {

    /** Login path. Referenced by {@link SecurityConfig} so the two cannot drift apart. */
    public static final String LOGIN_PATH = "/api/v1/auth/login";

    private final ResearchUser researchUser;
    private final JwtService jwtService;

    AuthController(ResearchUser researchUser, JwtService jwtService) {
        this.researchUser = researchUser;
        this.jwtService = jwtService;
    }

    @Operation(
            summary = "Log in and obtain a JWT",
            description = """
                    Verifies the credentials against the configured BCrypt hash and returns \
                    a signed HS256 token.

                    Copy the `token` value into Swagger UI's **Authorize** dialog, or send it as \
                    `Authorization: Bearer <token>` on every other `/api/v1` request.

                    The user name, password hash and signing secret come from `snrm.auth.*`. If no \
                    password hash is configured, the application generates a password at startup \
                    and prints it to the log.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated; token issued.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing or blank username/password.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Unknown user or wrong password.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @SecurityRequirements
    @PostMapping(path = LOGIN_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        if (!researchUser.matches(request.username(), request.password())) {
            throw new BadCredentialsException("Invalid username or password.");
        }
        return jwtService.issue(request.username());
    }
}
