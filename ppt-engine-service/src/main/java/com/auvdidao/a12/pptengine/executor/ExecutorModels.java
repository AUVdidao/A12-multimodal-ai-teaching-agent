package com.auvdidao.a12.pptengine.executor;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Versioned, file-backed boundary for the V1 same-package executor. */
public final class ExecutorModels {

    private ExecutorModels() {
    }

    public record ExecuteRequest(
            String contractVersion,
            String requestId,
            CompositionModels.GenerationJob generationJob,
            ContractModels.LockedPptSpecification specification,
            ContractModels.ConfirmedTemplateProfile templateProfile,
            CompositionModels.ApprovedAssetManifest approvedAssetManifest,
            CompositionModels.ComposedPresentationPlan plan,
            TemplateSourceBinding templateSource,
            List<ApprovedAssetFile> approvedAssetFiles) {
        public ExecuteRequest {
            approvedAssetFiles = approvedAssetFiles == null ? List.of() : List.copyOf(approvedAssetFiles);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateSourceBinding(
            @JsonProperty("storageKey")
            String absolutePath,
            String sha256,
            long size,
            String lastModifiedUtc,
            String templateId,
            Integer templateVersion,
            Integer sourceVersionId) {
        /** Compatibility constructor for the original file-identity-only boundary. */
        public TemplateSourceBinding(String absolutePath, String sha256, long size, String lastModifiedUtc) {
            this(absolutePath, sha256, size, lastModifiedUtc, null, null, null);
        }
    }

    public record ApprovedAssetFile(
            String assetRequirementId,
            String approvedAssetId,
            @JsonProperty("storageKey")
            String absolutePath,
            String sha256,
            long size,
            String lastModifiedUtc) {
        /** Compatibility constructor for direct unit fixtures; JSON production requests must carry both identity fields. */
        public ApprovedAssetFile(String assetRequirementId, String approvedAssetId,
                                 String absolutePath, String sha256) {
            this(assetRequirementId, approvedAssetId, absolutePath, sha256,
                    fileSize(absolutePath), lastModifiedUtc(absolutePath));
        }

        private static long fileSize(String path) {
            try { return java.nio.file.Files.size(java.nio.file.Path.of(path)); }
            catch (Exception ignored) { return -1L; }
        }

        private static String lastModifiedUtc(String path) {
            try { return java.nio.file.Files.getLastModifiedTime(java.nio.file.Path.of(path)).toInstant().toString(); }
            catch (Exception ignored) { return null; }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecuteResponse(
        String contractVersion,
        String requestId,
        String executionId,
        String engineVersion,
        ContractTypes.GenerationJobStatus status,
        String specificationChecksum,
        String templateProfileChecksum,
        String assetManifestChecksum,
        List<ArtifactReceipt> artifacts,
        List<ExecutorDiagnostic> feedback,
        BuildValidationResult buildValidation,
        OffsetDateTime generatedAt) {
        public ExecuteResponse {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuildValidationResult(
            boolean passed,
            int slideCount,
            String artifactSha256,
            long artifactSize) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArtifactReceipt(
            String artifactId,
            String artifactType,
            String storageKey,
            String sha256,
            long fileSize,
            String mediaType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArtifactReference(
            String absolutePath,
            String sha256,
            long size,
            int slideCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewReference(
            boolean available,
            String reason) {
    }

    public record ExecutorDiagnostic(
            String code,
            ContractTypes.DiagnosticSeverity severity,
            ContractTypes.DiagnosticImpact impact,
            String operationId,
            String slideId,
            Integer pageNumber,
            String assetRequirementId,
            String componentId,
            String slotId,
            String messageKey,
            Map<String, String> safeDetails) {
    }
}
