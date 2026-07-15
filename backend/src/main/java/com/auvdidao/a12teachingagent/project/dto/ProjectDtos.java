package com.auvdidao.a12teachingagent.project.dto;

import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record ProjectRequest(
            String projectName,
            @NotBlank(message = "courseName is required")
            String courseName,
            @NotBlank(message = "chapterTitle is required")
            String chapterTitle,
            String targetStudents,
            @Min(value = 1, message = "lessonDuration must be greater than 0")
            Integer lessonDuration,
            String description
    ) {
    }

    public record ProjectResponse(
            Long id,
            String projectName,
            String courseName,
            String chapterTitle,
            String targetStudents,
            Integer lessonDuration,
            String description,
            String modelMode,
            ProjectStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt
    ) {
    }

    public record RecentProjectResponse(
            ProjectResponse project,
            LocalDateTime lastVisitedAt,
            Integer visitCount
    ) {
    }
}
