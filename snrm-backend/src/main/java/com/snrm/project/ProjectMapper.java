package com.snrm.project;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Entity ↔ DTO mapping for the {@link Project} aggregate: JPA entities never cross the
 * API.
 *
 * <p>Read direction only. Writes go through {@link ProjectService}, which sets the owner from the
 * bearer token rather than from the request — a generated {@code toEntity} would need a constructor
 * argument it has no way to supply, and would invite exactly the mistake of trusting a
 * client-supplied owner.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProjectMapper {

    ProjectDto toDto(Project project);

    List<ProjectDto> toDtoList(List<Project> projects);
}
