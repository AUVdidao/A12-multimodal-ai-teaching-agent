package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SpecificationResponse;
import com.auvdidao.a12teachingagent.template.TemplateDtos.CapabilityViewResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;

import java.time.LocalDateTime;
import java.util.List;

public final class PlanningDtos {
    private PlanningDtos() { }

    public enum ProposalOperation { INITIAL_PROPOSAL, PATCH, NEW_DRAFT_VERSION }

    public record PlanningRequest(
            @NotNull @Positive Long templateId,
            @NotNull @Positive Long templateProfileVersionId,
            @NotNull ProposalOperation operation,
            Long baseSpecificationVersion,
            @Size(max = 64) String baseSpecificationChecksum,
            @NotBlank @Size(max = 128) String confirmedContextVersion,
            @NotNull @Min(1) @Max(200) Integer targetSlideCount,
            @NotNull @Min(0) @Max(20) Integer slideCountTolerance,
            @NotBlank @Size(max = 32) String locale,
            @NotNull @Valid TeachingContext teachingContext,
            @Size(max = 100) List<@Valid @NotNull SourceEvidence> evidence,
            @Size(max = 2000) String teacherInstruction,
            boolean explicitTeacherTrigger,
            @Positive Long modelConnectionId
    ) {
        public PlanningRequest {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public PlanningRequest(Long templateId, Long templateProfileVersionId, ProposalOperation operation,
                               Long baseSpecificationVersion, String baseSpecificationChecksum, String confirmedContextVersion,
                               Integer targetSlideCount, Integer slideCountTolerance, String locale,
                               TeachingContext teachingContext, List<SourceEvidence> evidence, String teacherInstruction,
                               boolean explicitTeacherTrigger) {
            this(templateId, templateProfileVersionId, operation, baseSpecificationVersion, baseSpecificationChecksum,
                    confirmedContextVersion, targetSlideCount, slideCountTolerance, locale, teachingContext, evidence,
                    teacherInstruction, explicitTeacherTrigger, null);
        }

        @AssertTrue(message = "Planning proposals and patches require an explicit teacher trigger")
        public boolean explicitTriggerForMutation() {
            return explicitTeacherTrigger;
        }
    }

    public record TeachingContext(
            @NotBlank @Size(max = 200) String courseName,
            @NotBlank @Size(max = 500) String topic,
            @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 500) String> teachingObjectives,
            @Size(max = 200) List<@NotBlank @Size(max = 2000) String> outline,
            @Size(max = 200) List<@NotBlank @Size(max = 2000) String> lessonPlan,
            @NotNull Boolean teacherConfirmed
    ) {
        public TeachingContext {
            teachingObjectives = teachingObjectives == null ? List.of() : List.copyOf(teachingObjectives);
            outline = outline == null ? List.of() : List.copyOf(outline);
            lessonPlan = lessonPlan == null ? List.of() : List.copyOf(lessonPlan);
        }
    }

    public record SourceEvidence(
            @NotBlank @Size(max = 128) String sourceId,
            @NotBlank @Size(max = 32) String sourceType,
            @NotBlank @Size(max = 4000) String excerpt
    ) { }

    public record PlanningProposalDocument(
            String contractVersion,
            String templateProfileId,
            Integer templateProfileVersion,
            Integer templateCapabilityViewVersion,
            String templateCapabilityViewChecksum,
            Integer targetSlideCount,
            Integer slideCountTolerance,
            String locale,
            String provider,
            String model,
            String aiSupplementPolicy,
            List<PlannedSlide> slides
    ) { }

    public record PlannedSlide(
            String slideId,
            Integer pageNumber,
            String title,
            String teachingGoal,
            PlannedLayout semanticLayout,
            List<PlannedContentBlock> contentBlocks,
            List<PlannedAssetRequirement> assetRequirements,
            List<PlannedProvenance> provenance,
            String notes
    ) { }

    public record PlannedLayout(String primaryRole, List<PlannedRegion> regions, String requestedTransform) { }
    public record PlannedRegion(String regionId, String semanticRole, String preferredPosition, Integer maxItems) { }
    public record PlannedContentBlock(String blockId, String type, String content, String sourceType, String sourceReference, Boolean locked) { }
    public record PlannedAssetRequirement(String assetId, String assetType, String source, String approvalStatus, Boolean required, String placementIntent) { }
    public record PlannedProvenance(String sourceType, String sourceReference) { }

    /** Explicit, auditable patch intent; it is never an instruction to mutate a DRAFT in place. */
    public record PlanningPatch(
            @NotNull @Positive Long baseSpecificationVersion,
            @NotBlank @Size(min = 64, max = 64) String baseSpecificationChecksum,
            @NotBlank @Size(max = 2000) String teacherInstruction
    ) { }

    public record PlanningResponse(
            String runId,
            String traceId,
            String executionStatus,
            String requestedProvider,
            String usedProvider,
            String usedModel,
            String rejectionReason,
            CapabilityViewResponse capabilityView,
            SpecificationResponse specification,
            PlanningProposalDocument proposal,
            LocalDateTime tracedAt
    ) { }

    public record TraceResponse(
            Long id,
            Long projectId,
            String runId,
            String traceId,
            ProposalOperation operation,
            String status,
            String requestedProvider,
            String usedProvider,
            String usedModel,
            String capabilityViewChecksum,
            String inputChecksum,
            String outputChecksum,
            String rejectionReason,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) { }

    public record CapabilityLookup(
            @NotNull @Positive Long templateId,
            @NotNull @Positive Long profileVersionId
    ) { }
}

