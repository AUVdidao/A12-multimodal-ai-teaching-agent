package com.auvdidao.a12teachingagent.intent.dto;

import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class TeachingIntentDtos {

    private TeachingIntentDtos() {
    }

    public record TeachingIntentEvidenceResponse(
            Long materialId,
            Long knowledgeChunkId,
            String sourceFilename,
            List<PurposeType> usageTypes,
            String hitReason,
            String contentExcerpt
    ) {
    }

    public record TeachingIntentResponse(
            Long id,
            Long projectId,
            Long requirementSummaryId,
            String generationGoal,
            String contentBasis,
            String teachingApproach,
            String interactionMode,
            List<String> outputTypes,
            String stylePreference,
            List<TeachingIntentEvidenceResponse> evidenceItems,
            TeachingIntentStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime confirmedAt,
            boolean prototype
    ) {
    }

    public record TeachingIntentUpdateRequest(
            @NotBlank(message = "Generation goal is required")
            @Size(max = 4000) String generationGoal,
            @NotBlank(message = "Content basis is required")
            @Size(max = 6000) String contentBasis,
            @NotBlank(message = "Teaching approach is required")
            @Size(max = 4000) String teachingApproach,
            @NotBlank(message = "Interaction mode is required")
            @Size(max = 500) String interactionMode,
            @NotEmpty(message = "At least one output type is required") List<String> outputTypes,
            @Size(max = 500) String stylePreference
    ) {
    }
}
