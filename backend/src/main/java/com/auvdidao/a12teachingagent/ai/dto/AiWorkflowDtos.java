package com.auvdidao.a12teachingagent.ai.dto;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.fasterxml.jackson.databind.JsonNode;
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
            List<String> requestedMissingFields,
            RequirementSummaryData projectContext
    ) {

        public ClarificationRequest(
                Long projectId,
                String rawRequirement,
                List<String> knownFields,
                GenerationMode generationMode,
                List<String> requestedMissingFields
        ) {
            this(projectId, rawRequirement, knownFields, generationMode, requestedMissingFields, null);
        }

        public ClarificationRequest(
                Long projectId,
                String rawRequirement,
                List<String> knownFields,
                GenerationMode generationMode
        ) {
            this(projectId, rawRequirement, knownFields, generationMode, List.of(), null);
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
            GenerationMode generationMode,
            RequirementSummaryData projectContext
    ) {

        public RequirementSummaryRequest(
                Long projectId,
                String rawRequirement,
                List<DialogTurn> dialogTurns,
                GenerationMode generationMode
        ) {
            this(projectId, rawRequirement, dialogTurns, generationMode, null);
        }

        public RequirementSummaryRequest {
            dialogTurns = dialogTurns == null ? List.of() : List.copyOf(dialogTurns);
        }
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
            String purpose,
            String materialText,
            List<String> purposeTypes,
            RequirementSummaryData courseContext
    ) {

        public MaterialAnalysisRequest(
                Long projectId,
                String fileName,
                String materialType,
                String purpose
        ) {
            this(projectId, fileName, materialType, purpose, null, List.of(), null);
        }

        public MaterialAnalysisRequest {
            purposeTypes = purposeTypes == null ? List.of() : List.copyOf(purposeTypes);
        }
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
            List<String> keywords,
            List<KnowledgeSnippet> candidateSnippets
    ) {

        public KnowledgeRetrievalRequest(
                Long projectId,
                String courseName,
                String chapterTopic,
                List<String> keywords
        ) {
            this(projectId, courseName, chapterTopic, keywords, List.of());
        }

        public KnowledgeRetrievalRequest {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            candidateSnippets = candidateSnippets == null ? List.of() : List.copyOf(candidateSnippets);
        }
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
            GenerationMode generationMode,
            List<String> teachingGoals,
            List<String> contentPriorities,
            List<String> interactionIdeas,
            GenerationConstraints constraints
    ) {

        public GenerationPlanRequest(
                Long projectId,
                String courseName,
                String chapterTopic,
                String targetAudience,
                List<String> outputTypes,
                GenerationMode generationMode
        ) {
            this(
                    projectId,
                    courseName,
                    chapterTopic,
                    targetAudience,
                    outputTypes,
                    generationMode,
                    List.of(),
                    List.of(),
                    List.of(),
                    null
            );
        }

        public GenerationPlanRequest {
            outputTypes = outputTypes == null ? List.of() : List.copyOf(outputTypes);
            teachingGoals = teachingGoals == null ? List.of() : List.copyOf(teachingGoals);
            contentPriorities = contentPriorities == null ? List.of() : List.copyOf(contentPriorities);
            interactionIdeas = interactionIdeas == null ? List.of() : List.copyOf(interactionIdeas);
        }
    }

    public record GenerationConstraints(
            Integer lessonDurationMinutes,
            Integer maximumSlides,
            Integer interactionMinutes,
            List<String> targetTypes
    ) {

        public GenerationConstraints {
            targetTypes = targetTypes == null ? List.of() : List.copyOf(targetTypes);
        }
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

    public record GenerationPlanSnapshot(
            @NotBlank String planRef,
            List<PlanSection> pptOutline,
            List<PlanSection> docOutline,
            List<String> interactionPlan
    ) {

        public GenerationPlanSnapshot {
            pptOutline = pptOutline == null ? List.of() : List.copyOf(pptOutline);
            docOutline = docOutline == null ? List.of() : List.copyOf(docOutline);
            interactionPlan = interactionPlan == null ? List.of() : List.copyOf(interactionPlan);
        }
    }

    public record StructuredContentRequest(
            @NotNull Long projectId,
            @NotNull GenerationPlanSnapshot generationPlan,
            List<KnowledgeSnippet> referenceContext,
            List<String> targetTypes
    ) {

        public StructuredContentRequest {
            referenceContext = referenceContext == null ? List.of() : List.copyOf(referenceContext);
            targetTypes = targetTypes == null ? List.of() : List.copyOf(targetTypes);
        }
    }

    public record StructuredArtifactDraft(
            String artifactType,
            String title,
            JsonNode contentJson,
            List<Map<String, Object>> assetSuggestions
    ) {

        public StructuredArtifactDraft {
            assetSuggestions = assetSuggestions == null ? List.of() : List.copyOf(assetSuggestions);
        }
    }

    public record StructuredContentResponse(
            String workflow,
            StructuredArtifactDraft pptContent,
            StructuredArtifactDraft docContent,
            StructuredArtifactDraft interactionContent,
            boolean fallbackToBackendDrafts
    ) {
    }

    public record RevisionRequest(
            @NotNull Long projectId,
            @NotNull Long artifactId,
            @NotBlank String instruction,
            @NotBlank String currentContent,
            String artifactType,
            String selectedLocator
    ) {

        public RevisionRequest(
                Long projectId,
                Long artifactId,
                String instruction,
                String currentContent
        ) {
            this(projectId, artifactId, instruction, currentContent, null, null);
        }
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
