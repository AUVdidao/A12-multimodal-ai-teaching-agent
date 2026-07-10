package com.auvdidao.a12teachingagent.requirement.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class RequirementInputDtos {

    private RequirementInputDtos() {
    }

    public record RequirementInputRequest(
            @Size(max = 100) String gradeLevel,
            @Size(max = 100) String subject,
            @Size(max = 200) String topic,
            @Size(max = 100) String lessonDuration,
            @Size(max = 4000) String teachingGoals,
            @Size(max = 4000) String keyPoints,
            @Size(max = 4000) String difficultPoints,
            @Size(max = 10) List<@Size(max = 50) String> outputTypes,
            @Size(max = 10000) String rawRequirementText
    ) {

        @AssertTrue(message = "topic and rawRequirementText cannot both be blank")
        public boolean isTopicOrRawRequirementTextPresent() {
            return hasText(topic) || hasText(rawRequirementText);
        }

        private static boolean hasText(String value) {
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
