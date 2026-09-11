package com.auvdidao.a12.pptengine.composition;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ComponentObjectAction.CLONE_FROM_SOURCE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ComponentObjectAction.USE_EXISTING_OBJECT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.FILL_ASSET_SLOT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.FILL_TEXT_SLOT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.PRESERVE_BASE_OBJECT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.COMPOSITION_REFERENCE_INVALID;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.SLIDE_COMPOSER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SlotBindingKind.ASSET;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SlotBindingKind.TEXT;

/** Builds a stable, side-effect-free object operation sequence. */
@Component
public class SlideComposer {

    private final ChecksumService checksumService;
    private final StablePlanIdFactory idFactory;

    public SlideComposer(ChecksumService checksumService, StablePlanIdFactory idFactory) {
        this.checksumService = checksumService;
        this.idFactory = idFactory;
    }

    public ComposerResult compose(
            CompositionModels.ValidatedExecutionPackage request,
            List<CompositionModels.ResolvedLayoutPlan> layouts) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        Map<String, CompositionModels.ResolvedLayoutPlan> layoutsBySlide = new LinkedHashMap<>();
        for (CompositionModels.ResolvedLayoutPlan layout : layouts) {
            if (layoutsBySlide.putIfAbsent(layout.slideId(), layout) != null) {
                diagnostics.add(composerError(
                        layout.slideId(), layout.pageNumber(), null, null, null, null,
                        "layout", "duplicate"));
            }
        }

        Map<String, ContractModels.TemplateComponent> components = new LinkedHashMap<>();
        for (ContractModels.TemplateComponent component : request.templateProfile().components()) {
            components.putIfAbsent(component.componentId(), component);
        }
        Map<String, ContractModels.TemplatePageReference> pages = new LinkedHashMap<>();
        for (ContractModels.TemplatePageReference page : request.templateProfile().templatePageReferences()) {
            pages.putIfAbsent(page.pageReferenceId(), page);
        }

        List<CompositionModels.ComposedSlidePlan> composedSlides = new ArrayList<>();
        for (ContractModels.LockedPptSlide slide : request.specification().slides()) {
            CompositionModels.ResolvedLayoutPlan layout = layoutsBySlide.get(slide.slideId());
            if (layout == null || layout.pageNumber() != slide.pageNumber()) {
                diagnostics.add(composerError(
                        slide.slideId(), slide.pageNumber(), null, null, null, null,
                        "layout", "missingOrMismatched"));
                continue;
            }
            ContractModels.TemplatePageReference page = pages.get(layout.templatePage().pageReferenceId());
            if (page == null || page.sourceSlide() != layout.templatePage().sourceSlide()) {
                diagnostics.add(composerError(
                        slide.slideId(), slide.pageNumber(), null, null, null, null,
                        "templatePage", "missingOrMismatched"));
                continue;
            }

            List<CompositionModels.CompositionOperation> operations = new ArrayList<>();
            appendBaseObjectOperations(slide, page, request.templateProfile(), operations);
            appendComponentOperations(slide, layout, page, components, operations, diagnostics);
            appendTextOperations(slide, layout, operations, diagnostics);
            appendAssetOperations(
                    slide, layout, request.manifestEntriesByRequirementId(), operations, diagnostics);
            composedSlides.add(new CompositionModels.ComposedSlidePlan(
                    slide.slideId(), slide.pageNumber(), layout, operations));
        }

        ContractModels.LockedPptSpecification specification = request.specification();
        ContractModels.ConfirmedTemplateProfile profile = request.templateProfile();
        CompositionModels.ApprovedAssetManifest manifest = request.approvedAssetManifest();
        CompositionModels.ComposedPresentationPlan withoutChecksum =
                new CompositionModels.ComposedPresentationPlan(
                        ContractTypes.COMPOSITION_PLAN_V2,
                        new CompositionModels.RequestReference(
                                request.requestId(), request.packageVersion()),
                        new CompositionModels.GenerationJobReference(
                                request.generationJob().generationJobId(),
                                request.generationJob().executionAttemptId(),
                                request.generationJob().jobBindingChecksum(),
                                request.generationJob().engineBuildVersion(),
                                request.generationJob().executorAdapterVersion(),
                                request.generationJob().fontEnvironmentVersion()),
                        new CompositionModels.SpecificationReference(
                                specification.specificationId(), specification.version(), specification.checksum()),
                        new CompositionModels.TemplateProfileReference(
                                profile.profileId(), profile.profileVersion(), profile.templateId(),
                                profile.templateVersion(), checksumService.computeProfile(profile)),
                        new CompositionModels.ApprovedAssetManifestReference(
                                manifest.manifestId(), manifest.manifestVersion(), manifest.manifestChecksum()),
                        textFitBoundary(request.generationJob()),
                        specification.slides().size(),
                        composedSlides,
                        null);
        String planChecksum = checksumService.computePlan(withoutChecksum);
        CompositionModels.ComposedPresentationPlan plan =
                new CompositionModels.ComposedPresentationPlan(
                        withoutChecksum.planContractVersion(),
                        withoutChecksum.requestReference(),
                        withoutChecksum.generationJobReference(),
                        withoutChecksum.specificationReference(),
                        withoutChecksum.templateProfileReference(),
                        withoutChecksum.approvedAssetManifestReference(),
                        withoutChecksum.textFitBoundary(),
                        withoutChecksum.originalSlideCount(),
                        withoutChecksum.slides(),
                        planChecksum);
        return new ComposerResult(plan, diagnostics);
    }

    private CompositionModels.TextFitBoundary textFitBoundary(
            CompositionModels.GenerationJob generationJob) {
        return new CompositionModels.TextFitBoundary(
                ContractTypes.TEXT_FIT_BOUNDARY_V1,
                ContractTypes.TextFitMode.NO_ADJUSTMENT_PROFILE_V1,
                generationJob.fontEnvironmentVersion(),
                ContractTypes.TextMeasurementStatus.NOT_IMPLEMENTED,
                true,
                true,
                true,
                false,
                false,
                false,
                false);
    }

    private void appendBaseObjectOperations(
            ContractModels.LockedPptSlide slide,
            ContractModels.TemplatePageReference page,
            ContractModels.ConfirmedTemplateProfile profile,
            List<CompositionModels.CompositionOperation> operations) {
        for (String objectId : page.objectIds()) {
            CompositionModels.StableNativeObjectReference reference =
                    new CompositionModels.StableNativeObjectReference(
                            ContractTypes.STABLE_NATIVE_OBJECT_REFERENCE_V1,
                            profile.templateId(), profile.templateVersion(),
                            ContractTypes.NativeObjectScope.SLIDE,
                            page.sourceSlide(), objectId, ContractTypes.ObjectType.UNKNOWN);
            operations.add(new CompositionModels.CompositionOperation(
                    idFactory.operationId(PRESERVE_BASE_OBJECT, slide.slideId(),
                            page.pageReferenceId(), reference.referenceVersion(), reference.templateId(),
                            Integer.toString(reference.templateVersion()), reference.scope().name(),
                            Integer.toString(reference.sourceSlide()), reference.objectId(),
                            reference.objectType().name()),
                    PRESERVE_BASE_OBJECT,
                    page.pageReferenceId(),
                    reference,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
    }

    private void appendComponentOperations(
            ContractModels.LockedPptSlide slide,
            CompositionModels.ResolvedLayoutPlan layout,
            ContractModels.TemplatePageReference page,
            Map<String, ContractModels.TemplateComponent> components,
            List<CompositionModels.CompositionOperation> operations,
            List<ContractModels.Diagnostic> diagnostics) {
        layout.componentPlacements().stream()
                .sorted(Comparator.comparing(CompositionModels.ResolvedComponentPlacement::componentId))
                .forEach(placement -> {
                    ContractModels.TemplateComponent component = components.get(placement.componentId());
                    if (component == null) {
                        diagnostics.add(composerError(
                                slide.slideId(), slide.pageNumber(), null, null,
                                placement.componentId(), null, "component", "missing"));
                        return;
                    }
                    for (CompositionModels.StableNativeObjectReference reference
                            : placement.nativeObjectReferences()) {
                        ContractTypes.ComponentObjectAction action =
                                reference.sourceSlide() == page.sourceSlide()
                                ? USE_EXISTING_OBJECT : CLONE_FROM_SOURCE;
                        operations.add(new CompositionModels.CompositionOperation(
                                idFactory.operationId(USE_OR_CLONE_COMPONENT_OBJECT, slide.slideId(),
                                        component.componentId(), reference.referenceVersion(),
                                        reference.templateId(), Integer.toString(reference.templateVersion()),
                                        reference.scope().name(), Integer.toString(reference.sourceSlide()),
                                        reference.objectId(), reference.objectType().name(), action.name()),
                                USE_OR_CLONE_COMPONENT_OBJECT,
                                null,
                                reference,
                                component.componentId(),
                                action,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
                    }
                });
    }

    private void appendTextOperations(
            ContractModels.LockedPptSlide slide,
            CompositionModels.ResolvedLayoutPlan layout,
            List<CompositionModels.CompositionOperation> operations,
            List<ContractModels.Diagnostic> diagnostics) {
        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            CompositionModels.SlotPlacement placement = exactlyOnePlacement(
                    slide, layout, TEXT, block.blockId(), block.blockId(), null, diagnostics);
            if (placement == null) {
                continue;
            }
            operations.add(new CompositionModels.CompositionOperation(
                    idFactory.operationId(FILL_TEXT_SLOT, slide.slideId(), block.blockId(),
                            placement.componentId(), placement.slotId()),
                    FILL_TEXT_SLOT,
                    null,
                    null,
                    placement.componentId(),
                    null,
                    placement.slotId(),
                    block.blockId(),
                    checksumService.sha256Utf8(block.content()),
                    null,
                    null,
                    null,
                    placement.bounds(),
                    placement.requestedTransform(),
                    placement.allowedTransform()));
        }
    }

    private void appendAssetOperations(
            ContractModels.LockedPptSlide slide,
            CompositionModels.ResolvedLayoutPlan layout,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            List<CompositionModels.CompositionOperation> operations,
            List<ContractModels.Diagnostic> diagnostics) {
        for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
            CompositionModels.ApprovedAssetManifestEntry manifestEntry = manifestEntries.get(asset.assetId());
            if (manifestEntry != null
                    && manifestEntry.resolution() == ContractTypes.AssetResolution.APPROVED_OMISSION) {
                continue;
            }
            CompositionModels.SlotPlacement placement = exactlyOnePlacement(
                    slide, layout, ASSET, asset.assetId(), null, asset.assetId(), diagnostics);
            if (placement == null) {
                continue;
            }
            operations.add(new CompositionModels.CompositionOperation(
                    idFactory.operationId(FILL_ASSET_SLOT, slide.slideId(), asset.assetId(),
                            manifestEntry.approvedAssetId(),
                            placement.componentId(), placement.slotId()),
                    FILL_ASSET_SLOT,
                    null,
                    null,
                    placement.componentId(),
                    null,
                    placement.slotId(),
                    null,
                    manifestEntry.contentSha256(),
                    asset.assetId(),
                    manifestEntry.approvedAssetId(),
                    manifestEntry.assetType(),
                    placement.bounds(),
                    placement.requestedTransform(),
                    placement.allowedTransform()));
        }
    }

    private CompositionModels.SlotPlacement exactlyOnePlacement(
            ContractModels.LockedPptSlide slide,
            CompositionModels.ResolvedLayoutPlan layout,
            ContractTypes.SlotBindingKind bindingKind,
            String bindingId,
            String blockId,
            String assetId,
            List<ContractModels.Diagnostic> diagnostics) {
        List<CompositionModels.SlotPlacement> matches = layout.slotPlacements().stream()
                .filter(item -> item.bindingKind() == bindingKind && item.bindingId().equals(bindingId))
                .toList();
        if (matches.size() != 1) {
            diagnostics.add(composerError(
                    slide.slideId(), slide.pageNumber(), blockId, assetId,
                    matches.isEmpty() ? null : matches.get(0).componentId(),
                    matches.isEmpty() ? null : matches.get(0).slotId(),
                    "slotPlacement", matches.isEmpty() ? "missing" : "multiple"));
            return null;
        }
        return matches.get(0);
    }

    private ContractModels.Diagnostic composerError(
            String slideId,
            int pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            String reference,
            String reason) {
        return DiagnosticFactory.stageError(
                SLIDE_COMPOSER,
                COMPOSITION_REFERENCE_INVALID,
                "composer.referenceInvalid",
                slideId, pageNumber, blockId, assetId, componentId, slotId,
                Map.of("reference", reference, "reason", reason));
    }

    public record ComposerResult(
            CompositionModels.ComposedPresentationPlan plan,
            List<ContractModels.Diagnostic> diagnostics) {
        public ComposerResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
