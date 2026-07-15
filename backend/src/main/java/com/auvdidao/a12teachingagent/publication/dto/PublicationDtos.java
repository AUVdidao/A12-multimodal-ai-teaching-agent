package com.auvdidao.a12teachingagent.publication.dto;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.publication.PublicationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class PublicationDtos {

    private PublicationDtos() {
    }

    public record CreatePublicationRequest(
            @NotNull @Positive Long approvalRequestId,
            @NotNull @Positive Long classId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String summary
    ) {
    }

    public record PublicationResponse(
            Long id,
            Long approvalRequestId,
            Long artifactVersionId,
            Long projectId,
            String projectName,
            Long courseId,
            String courseName,
            Long classId,
            String className,
            String title,
            String summary,
            Long publishedBy,
            String publishedByName,
            PublicationStatus status,
            LocalDateTime publishedAt,
            LocalDateTime withdrawnAt
    ) {
    }

    public record LearningTaskSummary(
            Long publicationId,
            Long approvalRequestId,
            Long artifactVersionId,
            Long projectId,
            String projectName,
            Long courseId,
            String courseName,
            Long classId,
            String className,
            String title,
            String summary,
            LocalDateTime publishedAt
    ) {
    }

    public record ArtifactVersionMetadata(
            Long id,
            Integer versionNumber,
            String description,
            Boolean finalVersion,
            LocalDateTime createdAt
    ) {
    }

    public record PublishedArtifact(
            ArtifactType artifactType,
            String title,
            String contentJson,
            Integer schemaVersion
    ) {
    }

    public record LearningTaskDetail(
            Long publicationId,
            Long approvalRequestId,
            Long artifactVersionId,
            Long projectId,
            String projectName,
            Long courseId,
            String courseName,
            Long classId,
            String className,
            String title,
            String summary,
            LocalDateTime publishedAt,
            ArtifactVersionMetadata artifactVersion,
            List<PublishedArtifact> artifacts
    ) {
    }
}
