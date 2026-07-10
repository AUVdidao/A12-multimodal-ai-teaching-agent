package com.auvdidao.a12teachingagent.summary.dto;

import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class RequirementSummaryDtos {

    private RequirementSummaryDtos() {
    }

    public record RequirementSummaryUpdateRequest(
            @Size(max = 100) String gradeLevel,
            @Size(max = 100) String subject,
            @Size(max = 200) String topic,
            @Size(max = 100) String lessonDuration,
            @Size(max = 4000) String teachingGoals,
            @Size(max = 4000) String keyPoints,
            @Size(max = 4000) String difficultPoints,
            @Size(max = 10) List<@Size(max = 50) String> outputTypes,
            @Size(max = 200) String stylePreference
    ) {
    }

    public record RequirementSummaryResponse(
            Long id,
            Long projectId,
            Long sourceRequirementId,
            String gradeLevel,
            String subject,
            String topic,
            String lessonDuration,
            String teachingGoals,
            String keyPoints,
            String difficultPoints,
            List<String> outputTypes,
            String stylePreference,
            String generationMode,
            RequirementSummaryStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime confirmedAt
    ) {
    }
}
