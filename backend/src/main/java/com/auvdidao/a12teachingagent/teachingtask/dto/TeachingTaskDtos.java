package com.auvdidao.a12teachingagent.teachingtask.dto;

import com.auvdidao.a12teachingagent.domain.common.TaskPriority;
import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class TeachingTaskDtos {

    private TeachingTaskDtos() {
    }

    public record TeachingTaskRequest(
            @NotBlank @Size(max = 160) String taskName,
            @NotNull Long courseId,
            Long classId,
            @NotBlank @Size(max = 160) String chapterTitle,
            @NotNull Long assigneeId,
            @NotBlank @Size(max = 5000) String requirements,
            @NotNull TaskPriority priority,
            @NotNull @Future LocalDateTime dueAt,
            /** Null clears the linked project association when updating a task. */
            Long linkedProjectId
    ) {
    }

    public record TaskStatusRequest(
            @NotNull TeachingTaskStatus status,
            @Size(max = 5000) String note
    ) {
    }

    public record TaskSubmissionRequest(
            @NotBlank @Size(max = 5000) String note,
            /** Null keeps the current association, which must still reference an active project. */
            Long linkedProjectId
    ) {
    }

    public record TeachingTaskResponse(
            Long id,
            String taskName,
            Long courseId,
            String courseName,
            Long classId,
            String className,
            String chapterTitle,
            Long assigneeId,
            String assigneeName,
            String requirements,
            TaskPriority priority,
            LocalDateTime dueAt,
            Long createdBy,
            String creatorName,
            Long linkedProjectId,
            TeachingTaskStatus taskStatus,
            boolean overdue,
            String submissionNote,
            String reviewNote,
            LocalDateTime submittedAt,
            LocalDateTime completedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
