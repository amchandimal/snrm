package com.snrm.project;

import com.snrm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Project CRUD ({@code GET/POST /projects}, {@code GET/PUT/DELETE /projects/{id}}).
 *
 * <p>Thin by design: the controller binds and validates, {@link ProjectService} decides and maps.
 * The owner never appears in a path or a body — it is read from the bearer token, so the API has no
 * way to express "somebody else's project".
 */
@Tag(name = "Projects", description = "Top-level containers for a modelling exercise (FR-01).")
@RestController
@RequestMapping(path = "/api/v1/projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectController {

    private final ProjectService projects;
    private final CurrentUser currentUser;

    ProjectController(ProjectService projects, CurrentUser currentUser) {
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @Operation(summary = "List the authenticated user's projects",
            description = "Ordered by name. Phase 1 has one research user, so this is every project.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The user's projects.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ProjectDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public List<ProjectDto> list() {
        return projects.list(currentUser.ownerId());
    }

    @Operation(summary = "Create a project",
            description = "The owner is taken from the bearer token. Names are unique per owner "
                    + "(`uq_project`).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the new project.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProjectDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, blank or too long.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` — this user already "
                    + "has a project with that name.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectDto> create(@Valid @RequestBody ProjectRequest request) {
        ProjectDto created = projects.create(currentUser.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id())).body(created);
    }

    @Operation(summary = "Fetch one project")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The project.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProjectDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{projectId}")
    public ProjectDto get(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId) {
        return projects.get(currentUser.ownerId(), projectId);
    }

    @Operation(summary = "Rename a project",
            description = "Full replacement of the client-supplied fields; the project currently "
                    + "has exactly one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated project.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProjectDto.class))),
            @ApiResponse(responseCode = "400", description = "Name missing, blank or too long.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`DUPLICATE_NAME` — another project of "
                    + "this user already has that name.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping(path = "/{projectId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProjectDto update(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId,
            @Valid @RequestBody ProjectRequest request) {
        return projects.update(currentUser.ownerId(), projectId, request);
    }

    @Operation(summary = "Delete a project and everything in it",
            description = """
                    Cascades along the ownership edges of the schema: networks, nodes, links, \
                    products, scenarios, simulation runs, metric results and time series all go \
                    with it.

                    Unlike editing a network, this is not blocked by completed simulation runs — \
                    freezing a network keeps results interpretable beside their inputs, \
                    and removing both together leaves nothing to misinterpret.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such project for this user.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId) {
        projects.delete(currentUser.ownerId(), projectId);
        return ResponseEntity.noContent().build();
    }
}
