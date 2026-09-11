package com.auvdidao.a12teachingagent.pptengine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.List;

final class PptEngineContracts {
    private PptEngineContracts() { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GenerationRequest(
            String contractVersion,
            ExecutionContext executionContext,
            SpecificationInput specification,
            ConfirmedProfileInput templateProfile,
            List<ApprovedManifestInput> approvedAssetManifest,
            ExecutionBindings executionBindings
    ) {
        GenerationRequest(
                String contractVersion,
                ExecutionContext executionContext,
                SpecificationInput specification,
                ConfirmedProfileInput templateProfile,
                List<ApprovedManifestInput> approvedAssetManifest) {
            this(contractVersion, executionContext, specification, templateProfile, approvedAssetManifest, null);
        }
    }

    /** Raw versioned wire objects produced only after the main domain bindings have been verified. */
    record ExecutionBindings(
            JsonNode generationJob,
            JsonNode specification,
            JsonNode templateProfile,
            JsonNode approvedAssetManifest,
            JsonNode plan,
            JsonNode templateSource,
            List<JsonNode> approvedAssetFiles,
            String specificationChecksum,
            String templateProfileChecksum,
            String assetManifestChecksum
    ) {
        ExecutionBindings {
            approvedAssetFiles = approvedAssetFiles == null ? List.of() : List.copyOf(approvedAssetFiles);
        }
    }

    record ExecutionContext(
            Long jobId,
            String executionId,
            Long projectId,
            Long ownerUserId,
            Long requestedBy,
            String engineVersion
    ) { }

    record SpecificationInput(
            Long id,
            String specificationId,
            Integer version,
            String status,
            String checksum,
            String contractVersion,
            String templateProfileId,
            Integer templateProfileVersion,
            String templateProfileChecksum,
            List<SlideInput> slides
    ) { }

    record SlideInput(
            String slideId,
            Integer pageNumber,
            String title,
            String teachingGoal,
            String semanticLayoutJson,
            List<ContentBlockInput> contentBlocks,
            List<AssetRequirementInput> assetRequirements,
            List<ProvenanceInput> provenance,
            String notes
    ) { }

    record ContentBlockInput(String blockId, String type, String content, String sourceType,
                             String sourceReference, Boolean locked) { }

    record AssetRequirementInput(String assetId, String assetType, String source,
                                 String approvalStatus, Boolean required, String placementIntent) { }

    record ProvenanceInput(String sourceType, String sourceReference) { }

    record ConfirmedProfileInput(
            Long id,
            Long templateId,
            Long sourceVersionId,
            Integer version,
            String checksum,
            Integer capabilityViewVersion,
            String capabilityViewChecksum,
            String profileJson,
            String capabilityViewJson,
            Long ownerUserId,
            String parserSnapshotChecksum,
            String confirmedChecksum
    ) { }

    record ApprovedManifestInput(
            Long id,
            Long projectId,
            Long ownerUserId,
            Long candidateAssetId,
            String assetKey,
            Integer manifestVersion,
            Integer candidateVersion,
            String requirementKey,
            String placementIntent,
            String sourceType,
            String sourceReference,
            String storageKey,
            String mimeType,
            Long fileSize,
            String sha256,
            String provider,
            String model,
            String status,
            String manifestChecksum,
            String approvedFileLastModifiedUtc
    ) { }

    enum EngineStatus { SUCCEEDED, PARTIAL, SUCCEEDED_WITH_FEEDBACK, FAILED }

    record ExecutionResponse(
            String contractVersion,
            String executionId,
            String engineVersion,
            EngineStatus status,
            String specificationChecksum,
            String templateProfileChecksum,
            String assetManifestChecksum,
            List<ArtifactReceipt> artifacts,
            List<FeedbackDiagnostic> feedback,
            String requestId,
            java.time.OffsetDateTime generatedAt
    ) {
        ExecutionResponse(
                String executionId,
                String engineVersion,
                EngineStatus status,
                String specificationChecksum,
                String templateProfileChecksum,
                String assetManifestChecksum,
                List<ArtifactReceipt> artifacts,
                List<String> feedback) {
            this(null, executionId, engineVersion, status, specificationChecksum, templateProfileChecksum,
                    assetManifestChecksum, artifacts,
                    feedback == null ? List.of() : feedback.stream()
                            .map(message -> new FeedbackDiagnostic("LEGACY_FEEDBACK", "WARNING", "FOLLOW_UP",
                                    null, null, null, null, null, null, message, Map.of()))
                            .toList(),
                    null, null);
        }
    }

    record FeedbackDiagnostic(
            String code,
            String severity,
            String impact,
            String operationId,
            String slideId,
            Integer pageNumber,
            String assetRequirementId,
            String componentId,
            String slotId,
            String messageKey,
            Map<String, String> safeDetails
    ) { }

    record ArtifactReceipt(String artifactId, String artifactType, String storageKey,
                           String sha256, Long fileSize, String mediaType) { }
}
