package com.auvdidao.a12teachingagent.pptskill.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class PptHarnessDtos {
    private PptHarnessDtos() { }

    public record StartRequest(
            String requestId,
            long projectId,
            String templateId,
            String templateVersion,
            String locale,
            int targetSlideCount,
            PptHarnessJobSnapshot jobSnapshot
    ) { }

    public record TemplateSelection(String templateId, String templateVersion) { }

    public record GenerationPreferences(
            String language,
            String style,
            String density,
            int targetSlideCount
    ) { }

    public record MaterialEvidence(
            Long materialId,
            String sourceName,
            Long chunkId,
            Integer chunkNo,
            String text,
            Double score,
            String hitReason,
            String section,
            String title
    ) { }

    public record JobResponse(
            String taskId,
            String requestId,
            long projectId,
            String status,
            String currentStep,
            int progressPercent,
            String message,
            String statusUrl,
            String eventsUrl,
            ArtifactRef artifact,
            QaSummary qa
    ) { }

    public record ArtifactRef(String fileName, long sizeBytes, String sha256, String downloadUrl) { }

    public record QaSummary(boolean passed, String qaLevel, List<String> warnings) { }

    public record QaReport(String taskId, String qaLevel, boolean passed, JsonNode report) { }
}
