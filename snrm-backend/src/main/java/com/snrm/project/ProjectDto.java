package com.snrm.project;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A project as the API returns it ({@code PROJECT}, DTO boundary).
 *
 * @param id        surrogate key
 * @param name      unique within the owner's projects
 * @param ownerId   the research user who owns it
 * @param createdAt audit timestamp
 * @param updatedAt audit timestamp
 */
@Schema(name = "Project",
        description = "Top-level container for one modelling exercise: its networks, products, "
                + "scenarios and configuration variants.")
public record ProjectDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Project name; unique per owner.", example = "Automotive tier-1 case")
        String name,

        @Schema(description = "Owner of the project. Constant while Phase 1 has one research user.",
                example = "1")
        Long ownerId,

        @Schema(description = "When the project was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the project last changed (UTC).")
        Instant updatedAt) {
}
