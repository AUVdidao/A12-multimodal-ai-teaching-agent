package com.auvdidao.a12teachingagent.pptskill.harness;

import java.util.List;

/** Structured, immutable context captured once when a PPT job is submitted. */
public record PptHarnessJobSnapshot(
        ProjectSnapshot project,
        RequirementSummarySnapshot requirementSummary,
        TeachingIntentSnapshot confirmedTeachingIntent,
        GenerationPlanSnapshot confirmedGenerationPlan,
        List<PptHarnessDtos.MaterialEvidence> materialEvidence,
        PptHarnessDtos.TemplateSelection templateSelection,
        PptHarnessDtos.GenerationPreferences generationPreferences
) {
    public PptHarnessJobSnapshot {
        materialEvidence = materialEvidence == null ? List.of() : List.copyOf(materialEvidence);
    }

    public record ProjectSnapshot(
            Long projectId,
            String projectName,
            String courseName,
            String chapterTopic,
            String targetAudience,
            Integer lessonDurationMinutes,
            String generationMode
    ) { }

    public record RequirementSummarySnapshot(
            Long summaryId,
            String subject,
            String courseName,
            String topic,
            String gradeLevel,
            String targetAudience,
            String lessonDuration,
            String teachingGoals,
            String keyPoints,
            String difficultPoints,
            String stylePreference,
            String interactionType,
            List<String> outputTypes
    ) {
        public RequirementSummarySnapshot {
            outputTypes = outputTypes == null ? List.of() : List.copyOf(outputTypes);
        }
    }

    public record TeachingIntentSnapshot(
            Long intentId,
            String generationGoal,
            List<String> generationGoals,
            String contentBasis,
            String primaryBasis,
            List<String> supplementalBasis,
            String targetAudience,
            Integer totalHours,
            String teachingApproach,
            String interactionMode,
            String teachingFormat,
            List<String> outputTypes,
            String stylePreference,
            String notes
    ) {
        public TeachingIntentSnapshot {
            generationGoals = generationGoals == null ? List.of() : List.copyOf(generationGoals);
            supplementalBasis = supplementalBasis == null ? List.of() : List.copyOf(supplementalBasis);
            outputTypes = outputTypes == null ? List.of() : List.copyOf(outputTypes);
        }
    }

    public record GenerationPlanSnapshot(
            Long planId,
            String provider,
            List<PptOutlineSection> pptOutline,
            List<String> interactionPlan
    ) {
        public GenerationPlanSnapshot {
            pptOutline = pptOutline == null ? List.of() : List.copyOf(pptOutline);
            interactionPlan = interactionPlan == null ? List.of() : List.copyOf(interactionPlan);
        }
    }

    public record PptOutlineSection(
            Integer order,
            String title,
            String description,
            List<String> points,
            String materialReference
    ) {
        public PptOutlineSection {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }
}
