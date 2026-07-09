package com.auvdidao.a12teachingagent.requirement.dto;

import jakarta.validation.constraints.AssertTrue;

import java.util.List;

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

    @AssertTrue(message = "topic 和 rawRequirementText 至少填写一个")
    public boolean isTopicOrRawRequirementTextPresent() {
        return hasText(topic) || hasText(rawRequirementText);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
