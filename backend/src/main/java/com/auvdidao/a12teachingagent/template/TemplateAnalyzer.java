package com.auvdidao.a12teachingagent.template;

import com.fasterxml.jackson.databind.JsonNode;

public interface TemplateAnalyzer {
    AnalysisResult analyze(JsonNode structuralSnapshot, String previewReference);

    /**
     * The production path carries the non-secret binding needed to prevent a
     * rendered preview from being analyzed for the wrong project/source.
     * Legacy adapters may keep implementing the two-argument method while the
     * service uses this binding-aware entry point.
     */
    default AnalysisResult analyze(AnalysisRequest request) {
        return analyze(request.structuralSnapshot(), request.previewReference());
    }

    record AnalysisRequest(
            JsonNode structuralSnapshot,
            String previewReference,
            long projectId,
            long sourceVersionId,
            long ownerUserId,
            String sourceSha256,
            String analysisRunId,
            Long missionId,
            Long missionFileId,
            Long renderedSlideSetId,
            Long processingRunId
    ) {
        public AnalysisRequest(
                JsonNode structuralSnapshot,
                String previewReference,
                long projectId,
                long sourceVersionId,
                long ownerUserId,
                String sourceSha256,
                String analysisRunId
        ) {
            this(structuralSnapshot, previewReference, projectId, sourceVersionId, ownerUserId,
                    sourceSha256, analysisRunId, null, null, null, null);
        }

        public AnalysisRequest {
            if (structuralSnapshot == null || previewReference == null || previewReference.isBlank()) {
                throw new IllegalArgumentException("analysis input is incomplete");
            }
            if (projectId <= 0 || sourceVersionId <= 0 || ownerUserId <= 0) {
                throw new IllegalArgumentException("analysis identity is invalid");
            }
            if (sourceSha256 == null || !sourceSha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("sourceSha256 is invalid");
            }
            if (analysisRunId == null || analysisRunId.isBlank() || analysisRunId.length() > 128) {
                throw new IllegalArgumentException("analysisRunId is invalid");
            }
            if (missionId != null && missionId <= 0) {
                throw new IllegalArgumentException("missionId is invalid");
            }
            if ((missionFileId != null && missionFileId <= 0)
                    || (renderedSlideSetId != null && renderedSlideSetId <= 0)
                    || (processingRunId != null && processingRunId <= 0)) {
                throw new IllegalArgumentException("analysis binding ids are invalid");
            }
        }
    }

    record AnalysisResult(
            boolean implemented,
            String adapter,
            JsonNode candidateProfile,
            String message,
            String provider,
            String model,
            String analysisRunId,
            String inputSha256,
            String outputSha256
    ) {
        public AnalysisResult(boolean implemented, String adapter, JsonNode candidateProfile, String message) {
            this(implemented, adapter, candidateProfile, message, null, null, null, null, null);
        }

        static AnalysisResult notImplemented(String adapter, String message) {
            return new AnalysisResult(false, adapter, null, message, null, null, null, null, null);
        }
    }
}
