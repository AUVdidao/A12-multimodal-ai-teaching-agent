package com.auvdidao.a12teachingagent.versioning;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.versioning.dto.ArtifactVersionDtos.ArtifactVersionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ArtifactVersionService {

    private final ArtifactVersionRepository artifactVersionRepository;
    private final GeneratedArtifactRepository generatedArtifactRepository;
    private final ProjectRepository projectRepository;
    private final CurrentUserService currentUserService;

    public ArtifactVersionService(
            ArtifactVersionRepository artifactVersionRepository,
            GeneratedArtifactRepository generatedArtifactRepository,
            ProjectRepository projectRepository,
            CurrentUserService currentUserService
    ) {
        this.artifactVersionRepository = artifactVersionRepository;
        this.generatedArtifactRepository = generatedArtifactRepository;
        this.projectRepository = projectRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public List<ArtifactVersionResponse> list(Long projectId) {
        requireOwnerProject(projectId);
        List<GeneratedArtifact> artifacts = generatedArtifactRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        Map<Long, Long> artifactCounts = artifacts.stream()
                .filter(artifact -> artifact.getVersionId() != null)
                .collect(Collectors.groupingBy(GeneratedArtifact::getVersionId, Collectors.counting()));

        return artifactVersionRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .filter(version -> artifactCounts.containsKey(version.getId()))
                .map(version -> toResponse(version, artifactCounts.get(version.getId()).intValue()))
                .toList();
    }

    @Transactional
    public ArtifactVersionResponse finalizeVersion(Long projectId, Long versionId) {
        requireOwnerProject(projectId);
        ArtifactVersion version = artifactVersionRepository.findById(versionId)
                .filter(item -> Objects.equals(projectId, item.getProjectId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Artifact version not found for project: " + projectId
                ));

        int artifactCount = countArtifacts(projectId, versionId);
        if (artifactCount == 0) {
            throw new ConflictException("Cannot finalize an artifact version without generated artifacts");
        }

        List<ArtifactVersion> versions = artifactVersionRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        boolean alreadyFinal = Boolean.TRUE.equals(version.getFinalVersion())
                && versions.stream()
                .filter(item -> !Objects.equals(item.getId(), versionId))
                .noneMatch(item -> Boolean.TRUE.equals(item.getFinalVersion()));
        if (!alreadyFinal) {
            versions.forEach(item -> item.setFinalVersion(Objects.equals(item.getId(), versionId)));
            artifactVersionRepository.saveAll(versions);
        }
        return toResponse(version, artifactCount);
    }

    private Project requireOwnerProject(Long projectId) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.TEACHER);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        if (!Objects.equals(project.getOwnerUserId(), user.userId())) {
            throw new ForbiddenException("This project belongs to another teacher");
        }
        return project;
    }

    private int countArtifacts(Long projectId, Long versionId) {
        return (int) generatedArtifactRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .filter(artifact -> Objects.equals(versionId, artifact.getVersionId()))
                .count();
    }

    private ArtifactVersionResponse toResponse(ArtifactVersion version, int artifactCount) {
        return new ArtifactVersionResponse(
                version.getId(),
                version.getProjectId(),
                version.getGenerationPlanId(),
                version.getVersionNumber(),
                version.getDescription(),
                Boolean.TRUE.equals(version.getFinalVersion()),
                artifactCount,
                version.getCreatedAt()
        );
    }
}
