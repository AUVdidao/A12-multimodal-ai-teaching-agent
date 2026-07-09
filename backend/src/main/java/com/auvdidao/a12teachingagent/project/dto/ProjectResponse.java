package com.auvdidao.a12teachingagent.project.dto;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;

import java.time.LocalDateTime;

public record ProjectResponse(
        Long id,
        String projectName,
        String courseName,
        String chapterTopic,
        String targetAudience,
        Integer lessonDurationMinutes,
        GenerationMode generationMode,
        ProjectStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
