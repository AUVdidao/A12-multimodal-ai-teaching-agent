package com.auvdidao.a12.pptengine.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.*;

/**
 * Versioned deterministic planning models. They are deliberately separate from
 * the frozen preflight DTOs so composition output can evolve independently.
 */
public final class CompositionModels {

    private CompositionModels() {
    }

    private static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    /**
     * Final composition input. It intentionally does not reuse the frozen
     * two-input EnginePreflightRequest.
     */
    public record EngineComposePlanRequest(
            String contractVersion,
            String requestId,
            GenerationJob generationJob,
            ContractModels.LockedPptSpecification specification,
            ContractModels.ConfirmedTemplateProfile templateProfile,
            ApprovedAssetManifest approvedAssetManifest) {
    }

    public record GenerationJob(
            String generationJobId,
            String executionAttemptId,
            String projectId,
            String ownerUserId,
            String jobBindingChecksum,
            ExecutionInputBinding specificationBinding,
            ExecutionInputBinding templateProfileBinding,
            ExecutionInputBinding approvedAssetManifestBinding,
            String composeContractVersion,
            String planContractVersion,
            String engineBuildVersion,
            String executorAdapterVersion,
            String fontEnvironmentVersion,
            String requestedBy,
            OffsetDateTime requestedAt,
            String idempotencyKey) {
        public GenerationJob(
                String generationJobId,
                String executionAttemptId,
                String jobBindingChecksum,
                ExecutionInputBinding specificationBinding,
                ExecutionInputBinding templateProfileBinding,
                ExecutionInputBinding approvedAssetManifestBinding,
                String composeContractVersion,
                String planContractVersion,
                String engineBuildVersion,
                String executorAdapterVersion,
                String fontEnvironmentVersion,
                String requestedBy,
                OffsetDateTime requestedAt,
                String idempotencyKey) {
            this(generationJobId, executionAttemptId, "project-001", "owner-001", jobBindingChecksum,
                    specificationBinding, templateProfileBinding, approvedAssetManifestBinding,
                    composeContractVersion, planContractVersion, engineBuildVersion, executorAdapterVersion,
                    fontEnvironmentVersion, requestedBy, requestedAt, idempotencyKey);
        }
    }

    public record ExecutionInputBinding(
            String inputId,
            int inputVersion,
            String inputChecksum) {
    }

    public record ApprovedAssetManifest(
            String contractVersion,
            String manifestId,
            String projectId,
            String ownerUserId,
            int manifestVersion,
            AssetManifestStatus status,
            String specificationId,
            int specificationVersion,
            String specificationChecksum,
            String manifestChecksum,
            List<ApprovedAssetManifestEntry> entries) {
        public ApprovedAssetManifest {
            entries = immutableList(entries);
        }

        public ApprovedAssetManifest(
                String contractVersion,
                String manifestId,
                int manifestVersion,
                AssetManifestStatus status,
                String specificationId,
                int specificationVersion,
                String specificationChecksum,
                String manifestChecksum,
                List<ApprovedAssetManifestEntry> entries) {
            this(contractVersion, manifestId, "project-001", "owner-001", manifestVersion, status,
                    specificationId, specificationVersion, specificationChecksum, manifestChecksum, entries);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovedAssetManifestEntry(
            String assetRequirementId,
            AssetResolution resolution,
            String approvedAssetId,
            AssetType assetType,
            String contentSha256,
            String storageKey,
            Long fileSize,
            OffsetDateTime lastModifiedUtc) {
        public ApprovedAssetManifestEntry(
                String assetRequirementId,
                AssetResolution resolution,
                String approvedAssetId,
                AssetType assetType,
                String contentSha256) {
            this(assetRequirementId, resolution, approvedAssetId, assetType, contentSha256,
                    approvedAssetId == null ? null : "synthetic/" + approvedAssetId,
                    approvedAssetId == null ? null : 1L,
                    approvedAssetId == null ? null : OffsetDateTime.parse("2026-08-24T00:00:00Z"));
        }
    }

    /**
     * Immutable output of the composition Contract Gate. Resolver, Layout and
     * Composer consume this package instead of independently trusting raw DTOs.
     */
    public record ValidatedExecutionPackage(
            String packageVersion,
            String requestId,
            GenerationJob generationJob,
            ContractModels.LockedPptSpecification specification,
            ContractModels.ConfirmedTemplateProfile templateProfile,
            ApprovedAssetManifest approvedAssetManifest,
            Map<String, ApprovedAssetManifestEntry> manifestEntriesByRequirementId) {
        public ValidatedExecutionPackage {
            manifestEntriesByRequirementId = immutableMap(manifestEntriesByRequirementId);
        }
    }

    public record EngineComposePlanResponse(
            String contractVersion,
            String planContractVersion,
            String feedbackContractVersion,
            String requestId,
            ComposedPresentationPlan plan,
            CompositionFeedback feedback) {
    }

    public record EngineComposePlanErrorResponse(
            String contractVersion,
            String feedbackContractVersion,
            String requestId,
            CompositionFeedback feedback) {
    }

    public record CompositionFeedback(
            String contractVersion,
            String feedbackContractVersion,
            String requestId,
            String generationJobId,
            String executionAttemptId,
            String specificationId,
            Integer specificationVersion,
            String specificationChecksum,
            String templateProfileId,
            Integer templateProfileVersion,
            GenerationJobStatus status,
            List<CompositionDiagnostic> diagnostics,
            OffsetDateTime generatedAt) {
        public CompositionFeedback {
            diagnostics = immutableList(diagnostics);
        }
    }

    /**
     * Compose-only diagnostic. The frozen preflight Diagnostic remains
     * unchanged; this wrapper adds the execution impact used to derive the Job
     * status and names the Specification-side asset identifier precisely.
     */
    public record CompositionDiagnostic(
            String code,
            DiagnosticSeverity severity,
            DiagnosticSource source,
            DiagnosticImpact impact,
            boolean retryable,
            String slideId,
            Integer pageNumber,
            String blockId,
            String assetRequirementId,
            String componentId,
            String slotId,
            String messageKey,
            Map<String, String> safeDetails) {
        public CompositionDiagnostic {
            safeDetails = immutableMap(safeDetails);
        }
    }

    public record RequestReference(
            String requestId,
            String inputContractVersion) {
    }

    public record GenerationJobReference(
            String generationJobId,
            String executionAttemptId,
            String jobBindingChecksum,
            String engineBuildVersion,
            String executorAdapterVersion,
            String fontEnvironmentVersion) {
    }

    public record SpecificationReference(
            String specificationId,
            int specificationVersion,
            String specificationChecksum) {
    }

    public record TemplateProfileReference(
            String profileId,
            int profileVersion,
            String templateId,
            int templateVersion,
            String profileContentSha256) {
    }

    public record ApprovedAssetManifestReference(
            String manifestId,
            int manifestVersion,
            String manifestChecksum) {
    }

    public record TemplatePageSelection(
            String pageReferenceId,
            int sourceSlide,
            String semanticRole,
            TemplatePageSelectionBasis selectionBasis) {
    }

    public record StableNativeObjectReference(
            String referenceVersion,
            String templateId,
            int templateVersion,
            NativeObjectScope scope,
            int sourceSlide,
            String objectId,
            ObjectType objectType) {
    }

    public record ResolvedComponentPlacement(
            String componentId,
            List<StableNativeObjectReference> nativeObjectReferences,
            TransformConstraint allowedTransform) {
        public ResolvedComponentPlacement {
            nativeObjectReferences = immutableList(nativeObjectReferences);
        }
    }

    public record ComponentSelection(
            SlotBindingKind bindingKind,
            String bindingId,
            String selectedComponentId,
            String selectedSlotId,
            ComponentSelectionBasis selectionBasis) {
    }

    public record SlotPlacement(
            String placementId,
            String componentId,
            String slotId,
            SlotBindingKind bindingKind,
            String bindingId,
            String semanticRole,
            String regionId,
            ContractModels.Bounds bounds,
            TransformConstraint requestedTransform,
            TransformConstraint allowedTransform) {
    }

    public record ResolvedLayoutPlan(
            String slideId,
            int pageNumber,
            ContractModels.PageSize pageSize,
            ContractModels.Bounds safeArea,
            TemplatePageSelection templatePage,
            List<ResolvedComponentPlacement> componentPlacements,
            List<ComponentSelection> componentSelections,
            List<SlotPlacement> slotPlacements) {
        public ResolvedLayoutPlan {
            componentPlacements = immutableList(componentPlacements);
            componentSelections = immutableList(componentSelections);
            slotPlacements = immutableList(slotPlacements);
        }
    }

    /**
     * Versioned no-adjustment boundary for this planning phase. It records that
     * real font measurement is unavailable and forbids every content-changing
     * or PowerPoint-controlled fitting behavior.
     */
    public record TextFitBoundary(
            String policyVersion,
            TextFitMode mode,
            String fontEnvironmentVersion,
            TextMeasurementStatus measurementStatus,
            boolean preserveCharacters,
            boolean preserveParagraphOrder,
            boolean preserveListHierarchy,
            boolean allowRewrite,
            boolean allowTruncate,
            boolean allowSlideSplitOrMerge,
            boolean allowPowerPointAutoFit) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompositionOperation(
            String operationId,
            CompositionOperationType operationType,
            String pageReferenceId,
            StableNativeObjectReference nativeObjectReference,
            String componentId,
            ComponentObjectAction componentObjectAction,
            String slotId,
            String blockId,
            String contentSha256,
            String assetRequirementId,
            String approvedAssetId,
            AssetType assetType,
            ContractModels.Bounds bounds,
            TransformConstraint requestedTransform,
            TransformConstraint allowedTransform) {
    }

    public record ComposedSlidePlan(
            String slideId,
            int pageNumber,
            ResolvedLayoutPlan layout,
            List<CompositionOperation> operations) {
        public ComposedSlidePlan {
            operations = immutableList(operations);
        }
    }

    public record ComposedPresentationPlan(
            String planContractVersion,
            RequestReference requestReference,
            GenerationJobReference generationJobReference,
            SpecificationReference specificationReference,
            TemplateProfileReference templateProfileReference,
            ApprovedAssetManifestReference approvedAssetManifestReference,
            TextFitBoundary textFitBoundary,
            int originalSlideCount,
            List<ComposedSlidePlan> slides,
            String planChecksum) {
        public ComposedPresentationPlan {
            slides = immutableList(slides);
        }
    }
}
