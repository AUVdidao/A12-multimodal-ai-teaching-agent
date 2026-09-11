package com.auvdidao.a12teachingagent.specification.dto;

import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class PptSpecificationDtos {
    private PptSpecificationDtos() {}

    public record SpecificationWriteRequest(
            @NotBlank @Size(max = 16) String contractVersion,
            @NotBlank @Size(max = 128) String templateProfileId,
            @NotNull @Min(1) @Max(100000) Integer templateProfileVersion,
            @NotNull @Min(1) @Max(100000) Integer templateCapabilityViewVersion,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String templateCapabilityViewChecksum,
            @NotNull @Min(1) @Max(200) Integer targetSlideCount,
            @NotNull @Min(0) @Max(20) Integer slideCountTolerance,
            @NotBlank @Size(max = 32) String locale,
            @NotBlank @Size(max = 64) String provider,
            @NotBlank @Size(max = 128) String model,
            @NotNull AiSupplementPolicyValue aiSupplementPolicy,
            @NotEmpty @Size(max = 200) List<@NotNull @Valid SlideWrite> slides,
            @JsonProperty("expectedChecksum") String expectedChecksum,
            @JsonProperty("expectedEntityVersion") Long expectedEntityVersion
    ) {
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public SpecificationWriteRequest(
                @JsonProperty("contractVersion") String contractVersion,
                @JsonProperty("templateProfileId") String templateProfileId,
                @JsonProperty("templateProfileVersion") Integer templateProfileVersion,
                @JsonProperty("templateCapabilityViewVersion") Integer templateCapabilityViewVersion,
                @JsonProperty("templateCapabilityViewChecksum") String templateCapabilityViewChecksum,
                @JsonProperty("targetSlideCount") Integer targetSlideCount,
                @JsonProperty("slideCountTolerance") Integer slideCountTolerance,
                @JsonProperty("locale") String locale,
                @JsonProperty("provider") String provider,
                @JsonProperty("model") String model,
                @JsonProperty("aiSupplementPolicy") AiSupplementPolicyValue aiSupplementPolicy,
                @JsonProperty("slides") List<SlideWrite> slides,
                @JsonProperty("expectedChecksum") String expectedChecksum,
                @JsonProperty("expectedEntityVersion") Long expectedEntityVersion
        ) {
            this.contractVersion = contractVersion;
            this.templateProfileId = templateProfileId;
            this.templateProfileVersion = templateProfileVersion;
            this.templateCapabilityViewVersion = templateCapabilityViewVersion;
            this.templateCapabilityViewChecksum = templateCapabilityViewChecksum;
            this.targetSlideCount = targetSlideCount;
            this.slideCountTolerance = slideCountTolerance;
            this.locale = locale;
            this.provider = provider;
            this.model = model;
            this.aiSupplementPolicy = aiSupplementPolicy;
            this.slides = slides;
            this.expectedChecksum = expectedChecksum;
            this.expectedEntityVersion = expectedEntityVersion;
        }
    }

    public record ProposalRequest(
            Long baseVersion,
            String baseChecksum,
            @NotNull ProposalOperation operation,
            @NotNull @Valid SpecificationWriteRequest specification
    ) {}

    public enum ProposalOperation { INITIAL_PROPOSAL, PATCH, NEW_DRAFT_VERSION }
    public enum AiSupplementPolicyValue { DISABLED, TEACHER_APPROVED_ONLY }

    public record SlideWrite(
            @NotBlank @Size(max = 128) String slideId,
            @NotNull @Min(1) @Max(200) Integer pageNumber,
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 2000) String teachingGoal,
            @NotNull @Valid SemanticLayout semanticLayout,
            @Size(max = 100) List<@NotNull @Valid ContentBlockWrite> contentBlocks,
            @Size(max = 100) List<@NotNull @Valid AssetRequirementWrite> assetRequirements,
            @Size(max = 20) List<@NotNull @Valid ProvenanceWrite> provenance,
            @Size(max = 2000) String notes
    ) {}

    public record ContentBlockWrite(
            @NotBlank @Size(max = 128) String blockId,
            @NotBlank @Size(max = 16) String type,
            @NotBlank @Size(max = 4000) String content,
            @NotBlank @Size(max = 16) String sourceType,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotNull Boolean locked
    ) {}

    public record AssetRequirementWrite(
            @NotBlank @Size(max = 128) String assetId,
            @NotBlank @Size(max = 16) String assetType,
            @NotBlank @Size(max = 500) String source,
            @NotBlank @Size(max = 16) String approvalStatus,
            @NotNull Boolean required,
            @NotBlank @Size(max = 16) String placementIntent
    ) {}

    public record ProvenanceWrite(
            @NotBlank @Size(max = 16) String sourceType,
            @NotBlank @Size(max = 500) String sourceReference
    ) {}

    public record SemanticLayout(
            @NotBlank @Size(max = 64) String primaryRole,
            @Size(max = 50) List<@NotNull @Valid SemanticRegion> regions,
            String requestedTransform
    ) {}

    public record SemanticRegion(
            @NotBlank @Size(max = 128) String regionId,
            @NotBlank @Size(max = 64) String semanticRole,
            @NotBlank @Size(max = 16) String preferredPosition,
            @NotNull @Min(1) @Max(100) Integer maxItems
    ) {}

    public record ChecksumActionRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String expectedChecksum
    ) {}

    public record ReturnToDraftRequest(
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String expectedChecksum
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpecificationResponse(
            Long id,
            String specificationId,
            Long projectId,
            Integer version,
            PptSpecificationStatus status,
            String contractVersion,
            String templateProfileId,
            Integer templateProfileVersion,
            String templateProfileChecksum,
            Integer templateCapabilityViewVersion,
            String templateCapabilityViewChecksum,
            Integer targetSlideCount,
            Integer slideCountTolerance,
            String locale,
            String provider,
            String model,
            AiSupplementPolicyValue aiSupplementPolicy,
            Long createdBy,
            Long updatedBy,
            Long submittedBy,
            LocalDateTime submittedAt,
            String submittedChecksum,
            Long lockedBy,
            LocalDateTime lockedAt,
            String checksum,
            Integer returnedFromVersion,
            String returnReason,
            LocalDateTime teacherEditingAt,
            Long entityVersion,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<SlideResponse> slides
    ) {}

    public record SlideResponse(
            String slideId,
            Integer pageNumber,
            String title,
            String teachingGoal,
            SemanticLayout semanticLayout,
            List<ContentBlockResponse> contentBlocks,
            List<AssetRequirementResponse> assetRequirements,
            List<ProvenanceResponse> provenance,
            String notes
    ) {}

    public record ContentBlockResponse(String blockId, String type, String content, String sourceType, String sourceReference, Boolean locked) {}
    public record AssetRequirementResponse(String assetId, String assetType, String source, String approvalStatus, Boolean required, String placementIntent) {}
    public record ProvenanceResponse(String sourceType, String sourceReference) {}

    public record HistoryResponse(
            Long projectId,
            String specificationId,
            SpecificationResponse latest,
            List<SpecificationResponse> versions
    ) {}
}
