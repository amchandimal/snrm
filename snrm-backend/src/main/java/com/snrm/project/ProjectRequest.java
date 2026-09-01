package com.snrm.project;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/projects} and {@code PUT /api/v1/projects/{id}}.
 *
 * <p>The owner is not a field: it comes from the bearer token, so a caller cannot create a project
 * belonging to somebody else.
 *
 * <p>{@link #name} is bounded at 160 characters to match {@code project.name VARCHAR(160)} in
 * {@code V2__domain.sql} — validation mirrors the schema so an over-long name is a 400 with a
 * readable message rather than a truncation or a driver error.
 */
@Schema(name = "ProjectRequest", description = "Fields of a project the client supplies.")
public record ProjectRequest(

        @Schema(description = "Project name; must be unique among this user's projects.",
                example = "Automotive tier-1 case", requiredMode = Schema.RequiredMode.REQUIRED,
                maxLength = 160)
        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name) {
}
