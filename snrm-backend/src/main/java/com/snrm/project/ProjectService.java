package com.snrm.project;

import com.snrm.common.DuplicateNameException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Project CRUD (FR-01).
 *
 * <p>Returns DTOs rather than entities, and maps inside the transaction. That is not stylistic:
 * {@code spring.jpa.open-in-view=false}, so a lazy association touched after the service returns
 * would throw. Every service in this codebase maps before it returns for the same reason.
 *
 * <p>Every method takes the owner id from the caller ({@code auth/CurrentUser}) and filters by it,
 * so a project belonging to someone else is not found rather than forbidden — a 404 leaks nothing
 * about what exists. With one research user in Phase 1 this never fires; it is written now so that
 * multi-user hardening is a change of what {@code CurrentUser} returns, not a sweep through every
 * query.
 */
@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectMapper mapper;

    ProjectService(ProjectRepository projects, ProjectMapper mapper) {
        this.projects = projects;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> list(long ownerId) {
        return mapper.toDtoList(projects.findByOwnerIdOrderByNameAsc(ownerId));
    }

    @Transactional(readOnly = true)
    public ProjectDto get(long ownerId, long projectId) {
        return mapper.toDto(require(ownerId, projectId));
    }

    /**
     * @throws DuplicateNameException if this owner already has a project with that name
     *                                ({@code uq_project})
     */
    public ProjectDto create(long ownerId, ProjectRequest request) {
        String name = request.name().trim();
        if (projects.existsByOwnerIdAndName(ownerId, name)) {
            throw new DuplicateNameException("project", "This user", name);
        }
        return mapper.toDto(projects.save(new Project(name, ownerId)));
    }

    /** Renames a project. The owner is never reassigned — there is nobody to reassign it to. */
    public ProjectDto update(long ownerId, long projectId, ProjectRequest request) {
        Project project = require(ownerId, projectId);
        String name = request.name().trim();
        if (!project.getName().equals(name) && projects.existsByOwnerIdAndName(ownerId, name)) {
            throw new DuplicateNameException("project", "This user", name);
        }
        project.setName(name);
        return mapper.toDto(project);
    }

    /**
     * Deletes a project and everything below it.
     *
     * <p>Unguarded, unlike a network delete: the foreign keys of {@code V2__domain.sql} cascade
     * from {@code project} through networks, nodes, links, products, scenarios, runs and results,
     * so this discards completed simulation results too. That is deliberate — a network is frozen
     * so results stay interpretable beside their inputs, and deleting both together leaves
     * nothing to misinterpret. Deleting the project is an explicit act on the whole experiment.
     */
    public void delete(long ownerId, long projectId) {
        projects.delete(require(ownerId, projectId));
    }

    /** The project, or {@link EntityNotFoundException} if it is missing or owned by someone else. */
    private Project require(long ownerId, long projectId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No project with id " + projectId));
    }
}
