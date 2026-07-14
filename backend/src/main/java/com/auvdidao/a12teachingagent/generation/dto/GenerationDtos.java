package com.auvdidao.a12teachingagent.generation.dto;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class GenerationDtos {

    private GenerationDtos() {
    }

    public record PlanSection(
            @NotNull @Positive Integer order,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String description
    ) {
    }

    public record GenerationPlanResponse(
            Long id,
            Long projectId,
            String provider,
            List<PlanSection> pptOutline,
            List<PlanSection> docOutline,
            List<String> interactionPlan,
            boolean confirmed,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record GenerationPlanUpdateRequest(
            @NotEmpty List<@NotNull @Valid PlanSection> pptOutline,
            @NotEmpty List<@NotNull @Valid PlanSection> docOutline,
            @NotEmpty List<@NotBlank @Size(max = 2000) String> interactionPlan
    ) {
    }

    public record ArtifactGenerationRequest(
            @NotNull @Positive Long planId
    ) {
    }

    public record ArtifactResponse(
            Long id,
            Long projectId,
            Long generationPlanId,
            Long versionId,
            Integer versionNumber,
            ArtifactType type,
            String title,
            Integer schemaVersion,
            JsonNode content,
            LocalDateTime createdAt
    ) {
    }

    public record TeachingIntentSummary(
            Long id,
            TeachingIntentStatus status,
            String generationGoal,
            List<String> generationGoals,
            String contentBasis,
            String primaryBasis,
            String teachingApproach,
            String teachingFormat,
            String interactionMode,
            List<String> outputTypes,
            String targetAudience,
            String stylePreference
    ) {
    }

    public record GenerationCapabilities(
            boolean canCreatePlan,
            boolean canEditPlan,
            boolean canConfirmPlan,
            boolean canGenerate,
            boolean canGenerateArtifacts,
            boolean canPreview
    ) {
    }

    public record GenerationWorkspaceResponse(
            Long projectId,
            String projectName,
            ProjectStatus projectStatus,
            String provider,
            TeachingIntentSummary teachingIntent,
            GenerationPlanResponse latestPlan,
            List<ArtifactResponse> artifacts,
            GenerationCapabilities capabilities
    ) {
    }

    public record PptContent(
            String deckTitle,
            String theme,
            List<PptSlide> slides
    ) {
    }

    public record PptSlide(
            Integer index,
            String kind,
            String title,
            String layout,
            List<String> points,
            String speakerNotes
    ) {
    }

    public record CourseInfo(
            String projectName,
            String courseName,
            String chapterTopic,
            String targetAudience,
            Integer lessonDurationMinutes,
            String generationMode
    ) {
    }

    public record TeachingProcessStep(
            String stage,
            Integer durationMinutes,
            String content,
            String teacherActivity,
            String studentActivity
    ) {
    }

    public record DocSection(
            Integer order,
            String title,
            List<String> paragraphs
    ) {
    }

    public record LessonPlanContent(
            String title,
            CourseInfo courseInfo,
            List<String> teachingGoals,
            List<String> keyPoints,
            List<String> difficultPoints,
            List<String> methods,
            List<TeachingProcessStep> teachingProcess,
            List<String> classroomActivities,
            List<String> homework,
            List<String> resourceNotes,
            List<DocSection> sections
    ) {
    }

    public record InteractionContent(
            String title,
            String instructions,
            List<InteractionQuestion> questions
    ) {
    }

    public record InteractionQuestion(
            String id,
            String question,
            List<String> options,
            Integer correctOption,
            String correctAnswer,
            String explanation
    ) {
    }
}
