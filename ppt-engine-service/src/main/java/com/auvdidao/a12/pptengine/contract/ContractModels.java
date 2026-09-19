package com.auvdidao.a12.pptengine.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.*;

public final class ContractModels {

    private ContractModels() {
    }

    private static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public record EnginePreflightRequest(
            String contractVersion,
            String requestId,
            LockedPptSpecification specification,
            ConfirmedTemplateProfile templateProfile) {
    }

    public record LockedPptSpecification(
            String contractVersion,
            String specificationId,
            String projectId,
            int version,
            SpecificationStatus status,
            String templateProfileId,
            int templateProfileVersion,
            int targetSlideCount,
            int slideCountTolerance,
            String locale,
            String provider,
            String model,
            String aiSupplementPolicy,
            String lockedBy,
            OffsetDateTime lockedAt,
            String checksum,
            List<LockedPptSlide> slides) {
        public LockedPptSpecification {
            slides = immutableList(slides);
        }
    }

    public record LockedPptSlide(
            String slideId,
            int pageNumber,
            String title,
            String teachingGoal,
            List<LockedPptContentBlock> contentBlocks,
            SemanticLayout semanticLayout,
            List<LockedPptAssetReference> assetRequirements,
            List<ProvenanceEntry> provenance,
            String notes) {
        public LockedPptSlide {
            contentBlocks = immutableList(contentBlocks);
            assetRequirements = immutableList(assetRequirements);
            provenance = immutableList(provenance);
        }
    }

    public record LockedPptContentBlock(
            String blockId,
            ContentType type,
            String content,
            ContentSourceType sourceType,
            String sourceReference,
            boolean locked) {
    }

    public record LockedPptAssetReference(
            String assetId,
            AssetType assetType,
            String source,
            ApprovalStatus approvalStatus,
            boolean required,
            PlacementIntent placementIntent) {
    }

    public record SemanticLayout(
            String primaryRole,
            List<SemanticRegion> regions,
            TransformConstraint requestedTransform,
            String pageType,
            List<String> informationHierarchy,
            String visualFocus,
            String contentDensity,
            List<String> componentRequirements,
            String sourceConstraint,
            Boolean preserveEditability) {
        /** Compatibility constructor for the original role/region-only contract. */
        public SemanticLayout(
                String primaryRole,
                List<SemanticRegion> regions,
                TransformConstraint requestedTransform) {
            this(primaryRole, regions, requestedTransform, null, List.of(), null, null,
                    List.of(), null, true);
        }

        public SemanticLayout {
            regions = immutableList(regions);
            informationHierarchy = immutableList(informationHierarchy);
            componentRequirements = immutableList(componentRequirements);
            preserveEditability = preserveEditability == null ? Boolean.TRUE : preserveEditability;
        }
    }

    public record SemanticRegion(
            String regionId,
            String semanticRole,
            PreferredPosition preferredPosition,
            int maxItems) {
    }

    public record ProvenanceEntry(
            ContentSourceType sourceType,
            String sourceReference) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfirmedTemplateProfile(
            String contractVersion,
            String profileId,
            String templateId,
            String projectId,
            String ownerUserId,
            int templateVersion,
            int profileVersion,
            TemplateProfileStatus status,
            PageSize pageSize,
            SpatialProfile spatialProfile,
            List<TemplatePageReference> templatePageReferences,
            List<TemplateComponent> components,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PreservedNativeObject> preservedNativeObjects,
            TextFitPolicy textFitPolicy,
            String executionStatus,
            Integer sourceVersionId,
            String sourceSha256,
            String parserSnapshotChecksum) {
        public ConfirmedTemplateProfile {
            templatePageReferences = immutableList(templatePageReferences);
            components = immutableList(components);
            preservedNativeObjects = immutableList(preservedNativeObjects);
        }

        /** Compatibility constructor for the frozen pre-TextFit profile shape. */
        public ConfirmedTemplateProfile(
                String contractVersion,
                String profileId,
                String templateId,
                int templateVersion,
                int profileVersion,
                TemplateProfileStatus status,
                PageSize pageSize,
                SpatialProfile spatialProfile,
                List<TemplatePageReference> templatePageReferences,
                List<TemplateComponent> components) {
            this(contractVersion, profileId, templateId, "project-001", "owner-001", templateVersion, profileVersion, status,
                    pageSize, spatialProfile, templatePageReferences, components, null,
                    null, null, null, null, null);
        }
    }

    /**
     * Teacher-confirmed, versioned typography constraints. It carries no text
     * and cannot authorize rewrite, truncation, splitting, merging, or AutoFit.
     */
    public record TextFitPolicy(
            String policyVersion,
            TextFitMode mode,
            String fontEnvironmentVersion,
            int minimumFontSizePt,
            int defaultFontSizePt,
            int maximumFontSizePt,
            int fontSizeStepPt,
            int minimumLineSpacingPct,
            int defaultLineSpacingPct,
            int maximumLineSpacingPct,
            int lineSpacingStepPct,
            int maxTextBoxGrowthWidthEmu,
            int maxTextBoxGrowthHeightEmu,
            int minimumParagraphSpacingPt,
            int defaultParagraphSpacingPt,
            int maximumParagraphSpacingPt,
            int paragraphSpacingStepPt,
            List<String> adjustmentOrder) {
        public TextFitPolicy {
            adjustmentOrder = immutableList(adjustmentOrder);
        }

        public static TextFitPolicy noAdjustmentDefault() {
            return new TextFitPolicy(
                    TEXT_FIT_BOUNDARY_V1,
                    TextFitMode.NO_ADJUSTMENT_PROFILE_V1,
                    "UNBOUND",
                    1, 1, 1, 1,
                    100, 100, 100, 1,
                    0, 0,
                    0, 0, 0, 1,
                    List.of());
        }
    }

    public record PageSize(int widthEmu, int heightEmu) {
    }

    public record SpatialProfile(
            int safeMarginLeftEmu,
            int safeMarginTopEmu,
            int safeMarginRightEmu,
            int safeMarginBottomEmu) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplatePageReference(
            String pageReferenceId,
            int sourceSlide,
            String semanticRole,
            List<String> objectIds,
            String semanticRoleSource) {
        public TemplatePageReference {
            objectIds = immutableList(objectIds);
        }

        /** Compatibility constructor for pre-0140 page-role profiles. */
        public TemplatePageReference(
                String pageReferenceId,
                int sourceSlide,
                String semanticRole,
                List<String> objectIds) {
            this(pageReferenceId, sourceSlide, semanticRole, objectIds, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateComponent(
            String componentId,
            String name,
            String semanticRole,
            int sourceSlide,
            List<StableObjectReference> shapeRefs,
            List<String> childComponentIds,
            List<ComponentSlot> slots,
            TransformConstraint transformConstraint,
            FixedStyle fixedStyle,
            boolean reusable,
            double confidence,
            boolean teacherConfirmed,
            String executionEligibility) {
        public TemplateComponent {
            shapeRefs = immutableList(shapeRefs);
            childComponentIds = immutableList(childComponentIds);
            slots = immutableList(slots);
        }

        /** Compatibility constructor for pre-0140 confirmed-profile fixtures. */
        public TemplateComponent(
                String componentId,
                String name,
                String semanticRole,
                int sourceSlide,
                List<StableObjectReference> shapeRefs,
                List<String> childComponentIds,
                List<ComponentSlot> slots,
                TransformConstraint transformConstraint,
                FixedStyle fixedStyle,
                boolean reusable,
                double confidence,
                boolean teacherConfirmed) {
            this(componentId, name, semanticRole, sourceSlide, shapeRefs, childComponentIds, slots,
                    transformConstraint, fixedStyle, reusable, confidence, teacherConfirmed, null);
        }
    }

    public record PreservedNativeObject(
            int sourceSlide,
            String stableNativeReference,
            ObjectType objectType,
            String ooxmlType,
            String geometry,
            OriginalBounds originalBounds,
            boolean placeholder,
            boolean contentPlaceholder,
            boolean textContent,
            String semanticRole,
            boolean fixedDecoration,
            String classification,
            String classificationReason,
            boolean editable,
            List<String> relationships,
            String relationshipEvidence) {
        public PreservedNativeObject {
            relationships = immutableList(relationships);
        }
    }

    public record OriginalBounds(
            Double x,
            Double y,
            Double width,
            Double height,
            String coordinateUnit) {
    }

    public record StableObjectReference(
            ObjectType objectType,
            String objectId) {
    }

    public record ComponentSlot(
            String slotId,
            String semanticRole,
            List<ContentType> acceptedContentTypes,
            Bounds bounds,
            boolean required,
            CapacityConstraint capacityConstraint) {
        public ComponentSlot {
            acceptedContentTypes = immutableList(acceptedContentTypes);
        }
    }

    public record Bounds(int leftEmu, int topEmu, int widthEmu, int heightEmu) {
    }

    public record CapacityConstraint(Integer maxCharacters, Integer maxItems) {
    }

    public record FixedStyle(
            String styleId,
            String fontToken,
            String colorToken,
            boolean preserveTheme) {
    }

    public record EnginePreflightResponse(
            String contractVersion,
            String requestId,
            PreflightExecutionPlan plan,
            GenerationFeedback feedback) {
    }

    public record PreflightExecutionPlan(
            String requestId,
            List<ResolvedSlidePlan> slides) {
        public PreflightExecutionPlan {
            slides = immutableList(slides);
        }
    }

    public record ResolvedSlidePlan(
            String slideId,
            int pageNumber,
            List<String> selectedComponentIds,
            Map<String, String> blockToSlotBindings,
            Map<String, String> assetToSlotBindings,
            List<String> unresolvedBlockIds,
            List<String> unresolvedAssetIds) {
        public ResolvedSlidePlan {
            selectedComponentIds = immutableList(selectedComponentIds);
            blockToSlotBindings = immutableMap(blockToSlotBindings);
            assetToSlotBindings = immutableMap(assetToSlotBindings);
            unresolvedBlockIds = immutableList(unresolvedBlockIds);
            unresolvedAssetIds = immutableList(unresolvedAssetIds);
        }
    }

    public record GenerationFeedback(
            String contractVersion,
            String requestId,
            String specificationId,
            Integer specificationVersion,
            String specificationChecksum,
            String templateProfileId,
            Integer templateProfileVersion,
            FeedbackOutcome outcome,
            List<Diagnostic> diagnostics,
            OffsetDateTime generatedAt) {
        public GenerationFeedback {
            diagnostics = immutableList(diagnostics);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Diagnostic(
            String code,
            DiagnosticSeverity severity,
            DiagnosticSource source,
            boolean retryable,
            String slideId,
            Integer pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            String messageKey,
            Map<String, String> safeDetails) {
        public Diagnostic {
            safeDetails = immutableMap(safeDetails);
        }
    }
}
