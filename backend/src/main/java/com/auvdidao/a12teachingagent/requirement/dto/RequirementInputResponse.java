package com.auvdidao.a12teachingagent.requirement.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RequirementInputResponse(
        Long id,
        Long projectId,
        String gradeLevel,
        String subject,
        String topic,
        String lessonDuration,
        String teachingGoals,
        String keyPoints,
        String difficultPoints,
        List<String> outputTypes,
        String rawRequirementText,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
