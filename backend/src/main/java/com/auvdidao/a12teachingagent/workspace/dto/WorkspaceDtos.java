package com.auvdidao.a12teachingagent.workspace.dto;

import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    public record ProjectCounts(
            long materialCount,
            long parsedMaterialCount,
            long knowledgeChunkCount,
            long artifactCount,
            long versionCount,
            long exportCount
    ) {
    }

    public record ProjectBrief(
            Long id,
            String projectName,
            String subtitle,
            String courseName,
            String chapterTitle,
            String targetStudents,
            Integer lessonDurationMinutes,
            String lessonDurationLabel,
            String modelMode,
            ProjectStatus status,
            String stage,
            String stageLabel,
            int progress,
            String nextAction,
            String actionPath,
            ProjectCounts counts,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record WorkspaceMetrics(
            long projectCount,
            long activeProjectCount,
            long pendingTaskCount,
            long materialCount,
            long confirmedIntentCount,
            long generatedArtifactCount
    ) {
    }

    public record PendingTask(
            String code,
            Long projectId,
            String title,
            String description,
            String priority,
            String actionPath,
            boolean derived
    ) {
    }

    public record Activity(
            String type,
            Long projectId,
            String title,
            String description,
            LocalDateTime occurredAt
    ) {
    }

    public record Suggestion(
            String code,
            Long projectId,
            String title,
            String description,
            String actionPath
    ) {
    }

    public record TeacherWorkspaceResponse(
            WorkspaceMetrics metrics,
            List<ProjectBrief> continueProjects,
            List<PendingTask> pendingTasks,
            List<Activity> recentActivities,
            List<Suggestion> suggestions,
            LocalDateTime generatedAt
    ) {
    }

    public record ProjectPageResponse(
            List<ProjectBrief> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            String sort,
            String query,
            String stage
    ) {
    }

    public record TimelineStep(
            String code,
            String label,
            String state,
            LocalDateTime completedAt
    ) {
    }

    public record ProjectOverviewMetrics(
            int overallProgress,
            long pptCount,
            long docxCount,
            long interactionCount,
            long uploadedMaterialCount,
            long parsedMaterialCount,
            long indexedMaterialCount,
            long knowledgeChunkCount,
            long versionCount,
            Integer currentVersion,
            boolean finalVersionConfirmed,
            long exportCount
    ) {
    }

    public record QuickAction(
            String code,
            String label,
            String path,
            boolean enabled
    ) {
    }

    public record ProjectOverviewResponse(
            ProjectBrief project,
            List<TimelineStep> timeline,
            ProjectOverviewMetrics metrics,
            List<Activity> recentActivities,
            List<QuickAction> quickActions
    ) {
    }

    public record RequirementInputView(
            Long id,
            Long projectId,
            String gradeLevel,
            String subject,
            String topic,
            String baselineLevel,
            String lessonDuration,
            String teachingGoals,
            String keyPoints,
            String difficultPoints,
            String stylePreference,
            String interactionType,
            List<String> outputTypes,
            String rawRequirementText,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record RequirementFieldState(
            String code,
            String label,
            String value,
            boolean completed
    ) {
    }

    public record RequirementCompleteness(
            int collected,
            int total,
            int percentage,
            boolean complete,
            List<RequirementFieldState> fields
    ) {
    }

    public record DialogMessageView(
            Long id,
            String sessionId,
            String sender,
            String content,
            Integer roundNo,
            LocalDateTime createdAt
    ) {
    }

    public record RequirementWorkspaceResponse(
            ProjectBrief project,
            RequirementInputView latestRequirement,
            List<DialogMessageView> dialogues,
            RequirementCompleteness completeness,
            List<String> suggestedQuestions,
            boolean canGenerateSummary
    ) {
    }

    public record RequirementSummaryView(
            Long id,
            Long sourceRequirementId,
            String gradeLevel,
            String subject,
            String topic,
            String baselineLevel,
            String lessonDuration,
            String teachingGoals,
            String keyPoints,
            String difficultPoints,
            List<String> outputTypes,
            String stylePreference,
            String interactionType,
            String generationMode,
            RequirementSummaryStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime confirmedAt
    ) {
    }

    public record RequirementSourceView(
            Long requirementId,
            String sourceType,
            LocalDateTime submittedAt
    ) {
    }

    public record RequirementSummaryWorkspaceResponse(
            ProjectBrief project,
            RequirementSummaryView summary,
            RequirementSourceView source,
            boolean editable,
            boolean canConfirm,
            List<String> nextStageCapabilities
    ) {
    }

    public record UploadPolicy(
            long maxFileSizeBytes,
            long maxFileSizeMb,
            List<String> supportedExtensions,
            boolean requiresConfirmedSummary,
            boolean uploadEnabled
    ) {
    }

    public record PurposeOption(
            PurposeType code,
            String label,
            String description
    ) {
    }

    public record ParsePreview(
            Long parseResultId,
            MaterialParseStatus status,
            String summary,
            List<String> keywords,
            List<String> applicableTeachingStages,
            String failureReason,
            LocalDateTime parsedAt,
            boolean prototype,
            String extractedTextPreview,
            Integer pageCount,
            List<String> sections,
            Integer chunkCount,
            Long parseDurationMs
    ) {
    }

    public record MaterialItem(
            Long id,
            String originalFilename,
            String fileExtension,
            MaterialFileType fileType,
            String contentType,
            long fileSize,
            String description,
            MaterialParseStatus parseStatus,
            List<PurposeType> usageTypes,
            String usageNote,
            LocalDateTime uploadedAt,
            String downloadPath,
            ParsePreview parsePreview
    ) {
    }

    public record MaterialStatistics(
            long total,
            long parsing,
            long parsed,
            long failed,
            long indexed
    ) {
    }

    public record MaterialWorkspaceResponse(
            ProjectBrief project,
            UploadPolicy uploadPolicy,
            List<PurposeOption> purposeOptions,
            MaterialStatistics statistics,
            List<MaterialItem> materials
    ) {
    }

    public record KnowledgeWorkspaceSearchRequest(
            @NotBlank(message = "Search query is required")
            @Size(max = 500) String query,
            @Min(value = 1, message = "materialId must be greater than 0") Long materialId,
            String matchMode,
            Boolean caseSensitive,
            @Min(value = 0, message = "page must be at least 0") Integer page,
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 50, message = "size must be at most 50") Integer size
    ) {
    }

    public record KnowledgeWorkspaceHit(
            Long chunkId,
            Long materialId,
            int chunkNo,
            int scorePercent,
            String title,
            String content,
            String sourceFilename,
            String sourceLocation,
            List<String> keywords,
            List<PurposeType> usageTypes,
            String hitReason
    ) {
    }

    public record KnowledgeWorkspaceSearchResponse(
            Long projectId,
            String query,
            String matchMode,
            boolean caseSensitive,
            int page,
            int size,
            long totalElements,
            int totalPages,
            List<KnowledgeWorkspaceHit> hits,
            String algorithm,
            boolean prototype
    ) {
    }

    public record IntentEvidenceView(
            Long materialId,
            Long knowledgeChunkId,
            String sourceFilename,
            List<PurposeType> usageTypes,
            String hitReason,
            String contentExcerpt
    ) {
    }

    public record TeachingIntentView(
            Long id,
            Long requirementSummaryId,
            List<String> generationGoals,
            String generationGoal,
            String primaryBasis,
            List<String> supplementalBasis,
            String contentBasis,
            String targetAudience,
            Integer totalHours,
            String teachingFormat,
            String teachingApproach,
            String interactionMode,
            List<String> outputTypes,
            String stylePreference,
            String notes,
            List<IntentEvidenceView> evidenceItems,
            TeachingIntentStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime confirmedAt
    ) {
    }

    public record IntentOption(
            String code,
            String label
    ) {
    }

    public record TeachingIntentOptions(
            List<IntentOption> generationGoals,
            List<IntentOption> contentBases,
            List<IntentOption> teachingFormats,
            List<IntentOption> outputTypes
    ) {
    }

    public record TeachingIntentWorkspaceResponse(
            ProjectBrief project,
            TeachingIntentView intent,
            TeachingIntentOptions options,
            boolean canGenerate,
            boolean canEdit,
            boolean canConfirm,
            int evidenceCount
    ) {
    }

    public record TeachingIntentWorkspaceUpdateRequest(
            @NotEmpty(message = "At least one generation goal is required")
            @Size(max = 8) List<@NotBlank @Size(max = 100) String> generationGoals,
            @NotBlank(message = "Primary content basis is required")
            @Size(max = 500) String primaryBasis,
            @Size(max = 10) List<@NotBlank @Size(max = 200) String> supplementalBasis,
            @NotBlank(message = "Target audience is required")
            @Size(max = 200) String targetAudience,
            @Min(value = 1, message = "totalHours must be greater than 0")
            @Max(value = 1000, message = "totalHours must be at most 1000") Integer totalHours,
            @NotBlank(message = "Teaching format is required")
            @Size(max = 200) String teachingFormat,
            @NotEmpty(message = "At least one output type is required")
            @Size(max = 10) List<@NotBlank @Size(max = 100) String> outputTypes,
            @Size(max = 200) String stylePreference,
            @Size(max = 2000) String notes
    ) {
    }
}
