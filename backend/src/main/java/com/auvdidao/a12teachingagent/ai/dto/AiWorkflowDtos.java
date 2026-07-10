package com.auvdidao.a12teachingagent.ai.dto;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class AiWorkflowDtos {

    private AiWorkflowDtos() {
    }

    public record AiGatewayStatus(
            String requestedProvider,
            String activeProvider,
            boolean mockEnabled,
            boolean difyConfigured,
            boolean fallbackToMock,
            String message
    ) {
    }

    public record DialogTurn(
            @NotBlank String role,
            @NotBlank String content
    ) {
    }

    public record RequirementSummaryData(
            String courseName,
            String chapterTopic,
            String targetAudience,
            Integer lessonDurationMinutes,
            List<String> teachingGoals,
            List<String> keyDifficulties,
            List<String> outputTypes,
            String coursewareStyle,
            String interactionType
    ) {
    }

    public record KnowledgeSnippet(
            String title,
            String sourceName,
            String content,
            double score
    ) {
    }

    public record PlanSection(
            String title,
            List<String> points,
            String materialReference
    ) {
    }

    public record ClarificationRequest(
            @NotNull Long projectId,
            @NotBlank String rawRequirement,
            List<String> knownFields,
            GenerationMode generationMode,
            List<String> requestedMissingFields
    ) {

        public ClarificationRequest(
                Long projectId,
                String rawRequirement,
                List<String> knownFields,
                GenerationMode generationMode
        ) {
            this(projectId, rawRequirement, knownFields, generationMode, List.of());
        }

        public ClarificationRequest {
            knownFields = knownFields == null ? List.of() : List.copyOf(knownFields);
            requestedMissingFields = requestedMissingFields == null
                    ? List.of()
                    : List.copyOf(requestedMissingFields);
        }
    }

    public record ClarificationResponse(
            String workflow,
            List<String> missingFields,
            List<String> questions,
            Map<String, String> suggestedFields,
            String nextAction
    ) {
    }

    public record RequirementSummaryRequest(
            @NotNull Long projectId,
            @NotBlank String rawRequirement,
            List<DialogTurn> dialogTurns,
            GenerationMode generationMode
    ) {
    }

    public record RequirementSummaryResponse(
            String workflow,
            RequirementSummaryData summary,
            List<String> assumptions,
            String confirmationQuestion
    ) {
    }

    public record MaterialAnalysisRequest(
            @NotNull Long projectId,
            @NotBlank String fileName,
            @NotBlank String materialType,
            String purpose
    ) {
    }

    public record MaterialAnalysisResponse(
            String workflow,
            String status,
            String summary,
            List<String> keywords,
            List<String> teachingUses,
            List<String> suggestedChunks
    ) {
    }

    public record KnowledgeRetrievalRequest(
            @NotNull Long projectId,
            @NotBlank String courseName,
            @NotBlank String chapterTopic,
            List<String> keywords
    ) {
    }

    public record KnowledgeRetrievalResponse(
            String workflow,
            List<KnowledgeSnippet> snippets,
            String retrievalNote
    ) {
    }

    public record TeachingIntentRequest(
            @NotNull Long projectId,
            RequirementSummaryData requirementSummary,
            List<KnowledgeSnippet> knowledgeSnippets
    ) {
    }

    public record TeachingIntentResponse(
            String workflow,
            String intentId,
            List<String> generationGoals,
            List<String> contentBasis,
            List<String> interactionIdeas,
            List<String> outputTypes,
            String confirmationPrompt
    ) {
    }

    public record GenerationPlanRequest(
            @NotNull Long projectId,
            @NotBlank String courseName,
            @NotBlank String chapterTopic,
            String targetAudience,
            List<String> outputTypes,
            GenerationMode generationMode
    ) {
    }

    public record GenerationPlanResponse(
            String workflow,
            String planId,
            List<PlanSection> pptOutline,
            List<PlanSection> docOutline,
            List<String> interactionPlan,
            String estimatedDuration,
            String nextAction
    ) {
    }

    public record RevisionRequest(
            @NotNull Long projectId,
            @NotNull Long artifactId,
            @NotBlank String instruction,
            @NotBlank String currentContent
    ) {
    }

    public record RevisionResponse(
            String workflow,
            String changeSummary,
            List<String> changedSections,
            String revisedContent,
            String versionSuggestion
    ) {
    }
}
