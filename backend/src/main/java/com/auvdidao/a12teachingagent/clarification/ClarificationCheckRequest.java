package com.auvdidao.a12teachingagent.clarification;

import java.util.List;

public record ClarificationCheckRequest(
        String gradeLevel,
        String subject,
        String topic,
        String lessonDuration,
        String teachingGoals,
        List<String> outputTypes,
        String rawRequirementText
) {
}
