package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingOperation;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileOrigin;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class TemplateDtos {
    private TemplateDtos() { }

    public record TemplateSummary(
            Long id,
            Long projectId,
            String name,
            Long activeSourceVersionId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) { }

    public record TemplateResponse(
            TemplateSummary template,
            boolean deduplicated,
            List<SourceVersionResponse> sourceVersions,
            List<ProfileSummary> profiles
    ) { }

    public record InternalCapabilityRequest(
            @Positive Long ownerUserId,
            @Positive Long missionId,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String templateFileSha256
    ) { }

    public record InternalCapabilityResponse(
            Long missionId,
            Long ownerUserId,
            Long projectId,
            Long templateId,
            Long sourceVersionId,
            String templateFileSha256,
            String templateFileVersion,
            String templateProfileVersion,
            JsonNode engineNativeProfile,
            String engineNativeProfileChecksum,
            String engineNativeProfileJson
    ) { }

    public record SourceVersionResponse(
            Long id,
            Long templateId,
            Integer version,
            String originalFilename,
            String contentType,
            Long fileSize,
            String sha256,
            TemplateProcessingStatus parseStatus,
            TemplateProcessingStatus renderStatus,
            TemplateProcessingStatus analysisStatus,
            LocalDateTime createdAt,
            String downloadPath,
            List<ProcessingRunResponse> processingRuns,
            StructuralSnapshotResponse structuralSnapshot,
            RenderedSlideSetResponse renderedSlideSet,
            boolean detailsLoaded
    ) { }

    public record ProcessingRunResponse(
            Long id,
            TemplateProcessingOperation operation,
            TemplateProcessingStatus status,
            Integer attempt,
            String adapter,
            String outputReference,
            String failureReason,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) { }

    public record StructuralSnapshotResponse(
            Long id,
            Integer slideCount,
            String checksum,
            JsonNode snapshot,
            LocalDateTime createdAt
    ) { }

    public record RenderedSlideSetResponse(
            Long id,
            TemplateProcessingStatus status,
            Integer slideCount,
            String previewReference,
            String statusMessage,
            String sourceSha256,
            String outputSha256,
            Long outputSizeBytes,
            String adapterVersion,
            LocalDateTime createdAt
    ) {
        public RenderedSlideSetResponse(Long id, TemplateProcessingStatus status, Integer slideCount,
                                        String previewReference, String statusMessage, LocalDateTime createdAt) {
            this(id, status, slideCount, previewReference, statusMessage, null, null, null, null, createdAt);
        }
    }

    public record ProcessingStatusResponse(
            Long sourceVersionId,
            TemplateProcessingOperation operation,
            TemplateProcessingStatus status,
            Integer attempt,
            String adapter,
            String outputReference,
            String failureReason
    ) { }

    public record ProfileSummary(
            Long id,
            Integer version,
            Long sourceVersionId,
            TemplateProfileStatus status,
            TemplateProfileOrigin origin,
            String checksum,
            String capabilityViewChecksum,
            LocalDateTime createdAt,
            LocalDateTime confirmedAt
    ) { }

    public record CandidateProfileRequest(
            @NotBlank(message = "Profile displayName is required")
            @Size(max = 200, message = "Profile displayName must be at most 200 characters")
            String displayName,
            @Size(max = 20, message = "At most 20 page roles are allowed")
            List<@NotBlank @Size(max = 80) String> pageRoles,
            @Size(max = 30, message = "At most 30 semantic layouts are allowed")
            List<@Valid SemanticLayout> semanticLayouts,
            @Valid Capability imageCapability,
            @Valid Capability tableCapability,
            @Valid Capability chartCapability,
            @Size(max = 30, message = "At most 30 fixed brand areas are allowed")
            List<@NotBlank @Size(max = 120) String> fixedBrandAreas,
            @Size(max = 50, message = "At most 50 limitations are allowed")
            List<@NotBlank @Size(max = 300) String> limitations
    ) { }

    public record SemanticLayout(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @PositiveOrZero Integer minCapacity,
            @PositiveOrZero Integer maxCapacity
    ) { }

    public record Capability(
            @NotNull Boolean supported,
            @PositiveOrZero Integer minCount,
            @PositiveOrZero Integer maxCount,
            @Size(max = 300) String notes
    ) { }

    public record ProfileResponse(
            Long id,
            Long templateId,
            Long projectId,
            Long sourceVersionId,
            Integer version,
            Long parentProfileVersionId,
            TemplateProfileStatus status,
            TemplateProfileOrigin origin,
            String parserSnapshotChecksum,
            JsonNode materialParseBinding,
            String materialParseBindingChecksum,
            TemplateProcessingStatus rendererStatus,
            TemplateProcessingStatus analyzerStatus,
            String analysisRunId,
            String analyzerInputSha256,
            String analyzerOutputSha256,
            String renderedOutputSha256,
            Long renderedOutputSizeBytes,
            String checksum,
            String capabilityViewChecksum,
            JsonNode profile,
            JsonNode analyzerProposal,
            CapabilityViewResponse capabilityView,
            JsonNode engineNativeProfile,
            String engineNativeProfileChecksum,
            Long ownedByTeacherId,
            LocalDateTime createdAt,
            LocalDateTime teacherEditedAt,
            LocalDateTime confirmedAt,
            String confirmedChecksum,
            List<ReviewResponse> reviews
    ) { }

    public record CapabilityViewResponse(
            Long profileVersionId,
            Integer profileVersion,
            String checksum,
            String displayName,
            List<String> pageRoles,
            List<SemanticLayout> semanticLayouts,
            Capability imageCapability,
            Capability tableCapability,
            Capability chartCapability,
            List<String> fixedBrandAreas,
            List<String> limitations
    ) { }

    public record ReviewResponse(
            Long id,
            String action,
            String checksumAtAction,
            String note,
            LocalDateTime createdAt
    ) { }

    public record ConfirmProfileRequest(
            @NotBlank(message = "checksum is required") String checksum,
            @Size(max = 500) String note
    ) { }

    public record RevisionRequest(
            @NotNull Long sourceVersionId,
            @Valid @NotNull CandidateProfileRequest profile,
            TemplateProfileOrigin origin,
            @jakarta.validation.constraints.Pattern(regexp = "^[0-9a-fA-F]{64}$") String parserSnapshotChecksum,
            @jakarta.validation.constraints.Size(max = 128) String analysisRunId
    ) {
        public RevisionRequest(Long sourceVersionId, CandidateProfileRequest profile,
                               TemplateProfileOrigin origin, String parserSnapshotChecksum) {
            this(sourceVersionId, profile, origin, parserSnapshotChecksum, null);
        }
    }

    public record RollbackRequest(@NotNull Long targetProfileVersionId) { }
}
