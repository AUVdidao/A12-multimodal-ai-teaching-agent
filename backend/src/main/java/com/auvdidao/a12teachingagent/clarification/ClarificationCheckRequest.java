package com.auvdidao.a12teachingagent.clarification;

import jakarta.validation.constraints.Size;

import java.util.List;

public record ClarificationCheckRequest(
        String gradeLevel,
        String subject,
        String topic,
        String lessonDuration,
        String teachingGoals,
        String keyPoints,
        String difficultPoints,
        List<String> outputTypes,
        @Size(max = 10000, message = "rawRequirementText must not exceed 10000 characters")
        String rawRequirementText
) {
}
