package com.auvdidao.a12teachingagent.security;

import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ProjectAccessService {

    private final CurrentUserService currentUserService;
    private final ProjectRepository projectRepository;

    public ProjectAccessService(CurrentUserService currentUserService, ProjectRepository projectRepository) {
        this.currentUserService = currentUserService;
        this.projectRepository = projectRepository;
    }

    public void requireAccess(Long projectId) {
        Optional<AuthenticatedUser> currentUser = currentUserService.currentUser();
        if (currentUser.isEmpty()) {
            return;
        }
        AuthenticatedUser teacher = requireTeacher(currentUser.get());
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        requireActive(project);
        requireOwner(teacher, project);
    }

    public void requireAccess(Project project) {
        currentUserService.currentUser().ifPresent(user -> requireAccess(user, project));
    }

    public List<Project> filterAccessibleProjects(List<Project> projects) {
        Optional<AuthenticatedUser> currentUser = currentUserService.currentUser();
        if (currentUser.isEmpty()) {
            return projects;
        }
        AuthenticatedUser teacher = requireTeacher(currentUser.get());
        return projects.stream()
                .filter(project -> project.getDeletedAt() == null)
                .filter(project -> Objects.equals(project.getOwnerUserId(), teacher.userId()))
                .toList();
    }

    private void requireAccess(AuthenticatedUser user, Project project) {
        AuthenticatedUser teacher = requireTeacher(user);
        requireActive(project);
        requireOwner(teacher, project);
    }

    private void requireActive(Project project) {
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + project.getId());
        }
    }

    private void requireOwner(AuthenticatedUser teacher, Project project) {
        if (!Objects.equals(project.getOwnerUserId(), teacher.userId())) {
            throw new ForbiddenException("This project belongs to another teacher");
        }
    }

    private AuthenticatedUser requireTeacher(AuthenticatedUser user) {
        if (user.activeRole() != UserRole.TEACHER) {
            throw new ForbiddenException("The active role is not allowed to access teacher projects");
        }
        return user;
    }
}
