package com.auvdidao.a12teachingagent.requirement.dto;

import jakarta.validation.constraints.AssertTrue;

import java.time.LocalDateTime;
import java.util.List;

public final class RequirementInputDtos {

    private RequirementInputDtos() {
    }

    public record RequirementInputRequest(
            String gradeLevel,
            String subject,
            String topic,
            String lessonDuration,
            String teachingGoals,
            String keyPoints,
            String difficultPoints,
            List<String> outputTypes,
            String rawRequirementText
    ) {

        @AssertTrue(message = "topic and rawRequirementText cannot both be blank")
        public boolean isTopicOrRawRequirementTextPresent() {
            return hasText(topic) || hasText(rawRequirementText);
        }

        private boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }

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
}
