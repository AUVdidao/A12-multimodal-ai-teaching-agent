package com.auvdidao.a12.pptengine.composition;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.layout.LayoutVariantCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetResolution.APPROVED_ASSET;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ComponentObjectAction.CLONE_FROM_SOURCE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ComponentObjectAction.USE_EXISTING_OBJECT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.FILL_ASSET_SLOT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.FILL_TEXT_SLOT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.PRESERVE_BASE_OBJECT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.COMPOSITION_REFERENCE_INVALID;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.PLAN_CHECKSUM_MISMATCH;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.PLAN_VALIDATOR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.FULL_BLEED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SlotBindingKind.ASSET;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SlotBindingKind.TEXT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TemplatePageSelectionBasis.EXACT_SEMANTIC_ROLE_STABLE_ORDER;

/** Final fail-closed gate. It reports defects but never repairs a plan. */
@Component
public class CompositionPlanValidator {

    private static final Set<String> FORBIDDEN_PLAN_FIELDS = Set.of(
            "content", "source", "sourceReference", "provider", "model",
            "apiKey", "credential", "credentials", "providerResponse", "generatedAt");

    private final ChecksumService checksumService;
    private final StablePlanIdFactory idFactory;
    private final ObjectMapper objectMapper;
    private final LayoutVariantCompiler variantCompiler;

    public CompositionPlanValidator(
            ChecksumService checksumService,
            StablePlanIdFactory idFactory,
            ObjectMapper objectMapper) {
        this(checksumService, idFactory, objectMapper, new LayoutVariantCompiler());
    }

    @Autowired
    public CompositionPlanValidator(
            ChecksumService checksumService,
            StablePlanIdFactory idFactory,
            ObjectMapper objectMapper,
            LayoutVariantCompiler variantCompiler) {
        this.checksumService = checksumService;
        this.idFactory = idFactory;
        this.objectMapper = objectMapper;
        this.variantCompiler = variantCompiler;
    }

    public List<ContractModels.Diagnostic> validate(
            CompositionModels.ValidatedExecutionPackage request,
            CompositionModels.ComposedPresentationPlan plan) {
        return validate(request, plan, PartialPlanContext.complete());
    }

    public List<ContractModels.Diagnostic> validate(
            CompositionModels.ValidatedExecutionPackage request,
            CompositionModels.ComposedPresentationPlan plan,
            PartialPlanContext partialContext) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        if (plan == null) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "plan", "missing"));
            return diagnostics;
        }

        validateRootReferences(request, plan, partialContext, diagnostics);
        validateNoForbiddenFields(plan, diagnostics);
        validateSlides(request, plan, partialContext, diagnostics);
        String expectedChecksum = checksumService.computePlan(plan);
        if (!expectedChecksum.equals(plan.planChecksum())) {
            diagnostics.add(DiagnosticFactory.stageError(
                    PLAN_VALIDATOR,
                    PLAN_CHECKSUM_MISMATCH,
                    "plan.checksumMismatch",
                    null, null, null, null, null, null,
                    Map.of("expectedChecksum", expectedChecksum)));
        }
        return List.copyOf(diagnostics);
    }

    private void validateRootReferences(
            CompositionModels.ValidatedExecutionPackage request,
            CompositionModels.ComposedPresentationPlan plan,
            PartialPlanContext partialContext,
            List<ContractModels.Diagnostic> diagnostics) {
        ContractModels.LockedPptSpecification specification = request.specification();
        ContractModels.ConfirmedTemplateProfile profile = request.templateProfile();
        CompositionModels.ApprovedAssetManifest manifest = request.approvedAssetManifest();
        boolean requestMatches = plan.requestReference() != null
                && request.requestId().equals(plan.requestReference().requestId())
                && request.packageVersion().equals(plan.requestReference().inputContractVersion());
        CompositionModels.GenerationJob job = request.generationJob();
        boolean generationJobMatches = plan.generationJobReference() != null
                && job.generationJobId().equals(plan.generationJobReference().generationJobId())
                && job.executionAttemptId().equals(plan.generationJobReference().executionAttemptId())
                && job.jobBindingChecksum().equals(plan.generationJobReference().jobBindingChecksum())
                && job.engineBuildVersion().equals(plan.generationJobReference().engineBuildVersion())
                && job.executorAdapterVersion().equals(
                plan.generationJobReference().executorAdapterVersion())
                && job.fontEnvironmentVersion().equals(
                plan.generationJobReference().fontEnvironmentVersion());
        boolean specificationMatches = plan.specificationReference() != null
                && specification.specificationId().equals(plan.specificationReference().specificationId())
                && specification.version() == plan.specificationReference().specificationVersion()
                && specification.checksum().equals(plan.specificationReference().specificationChecksum());
        boolean profileMatches = plan.templateProfileReference() != null
                && profile.profileId().equals(plan.templateProfileReference().profileId())
                && profile.profileVersion() == plan.templateProfileReference().profileVersion()
                && profile.templateId().equals(plan.templateProfileReference().templateId())
                && profile.templateVersion() == plan.templateProfileReference().templateVersion()
                && checksumService.computeProfile(profile)
                .equals(plan.templateProfileReference().profileContentSha256());
        boolean manifestMatches = plan.approvedAssetManifestReference() != null
                && manifest.manifestId().equals(plan.approvedAssetManifestReference().manifestId())
                && manifest.manifestVersion() == plan.approvedAssetManifestReference().manifestVersion()
                && manifest.manifestChecksum()
                .equals(plan.approvedAssetManifestReference().manifestChecksum());
        if (!ContractTypes.COMPOSITION_PLAN_V2.equals(plan.planContractVersion())) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "planContractVersion", "mismatch"));
        }
        if (!requestMatches) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "requestReference", "mismatch"));
        }
        if (!generationJobMatches) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "generationJobReference", "mismatch"));
        }
        if (!specificationMatches) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "specificationReference", "mismatch"));
        }
        if (!profileMatches) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "templateProfileReference", "mismatch"));
        }
        if (!manifestMatches) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "approvedAssetManifestReference", "mismatch"));
        }
        if (plan.originalSlideCount() != specification.slides().size()
                || plan.slides().size() != specification.slides().size()) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "slideCount", "mismatch"));
        }
        validateTextFitBoundary(request, plan, diagnostics);
    }

    private void validateTextFitBoundary(
            CompositionModels.ValidatedExecutionPackage request,
            CompositionModels.ComposedPresentationPlan plan,
            List<ContractModels.Diagnostic> diagnostics) {
        CompositionModels.TextFitBoundary boundary = plan.textFitBoundary();
        boolean valid = boundary != null
                && ContractTypes.TEXT_FIT_BOUNDARY_V1.equals(boundary.policyVersion())
                && boundary.mode() == ContractTypes.TextFitMode.NO_ADJUSTMENT_PROFILE_V1
                && request.generationJob().fontEnvironmentVersion()
                .equals(boundary.fontEnvironmentVersion())
                && boundary.measurementStatus() == ContractTypes.TextMeasurementStatus.NOT_IMPLEMENTED
                && boundary.preserveCharacters()
                && boundary.preserveParagraphOrder()
                && boundary.preserveListHierarchy()
                && !boundary.allowRewrite()
                && !boundary.allowTruncate()
                && !boundary.allowSlideSplitOrMerge()
                && !boundary.allowPowerPointAutoFit();
        if (!valid) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "textFitBoundary", "invalid"));
        }
    }

    private void validateNoForbiddenFields(
            CompositionModels.ComposedPresentationPlan plan,
            List<ContractModels.Diagnostic> diagnostics) {
        JsonNode tree = objectMapper.valueToTree(plan);
        Set<String> found = new HashSet<>();
        collectForbiddenFields(tree, found);
        if (!found.isEmpty()) {
            diagnostics.add(error(null, null, null, null, null, null,
                    "forbiddenPlanField", found.stream().sorted().findFirst().orElse("unknown")));
        }
    }

    private void collectForbiddenFields(JsonNode node, Set<String> found) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (FORBIDDEN_PLAN_FIELDS.contains(entry.getKey())) {
                    found.add(entry.getKey());
                }
                collectForbiddenFields(entry.getValue(), found);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectForbiddenFields(child, found));
        }
    }

    private void validateSlides(
            CompositionModels.ValidatedExecutionPackage request,
            CompositionModels.ComposedPresentationPlan plan,
            PartialPlanContext partialContext,
            List<ContractModels.Diagnostic> diagnostics) {
        Map<String, ContractModels.TemplatePageReference> pages = new LinkedHashMap<>();
        Map<String, ContractModels.TemplateComponent> components = new LinkedHashMap<>();
        Map<String, SlotOwner> slots = new LinkedHashMap<>();
        Map<Integer, Set<String>> objectsBySourceSlide = new HashMap<>();
        for (ContractModels.TemplatePageReference page : request.templateProfile().templatePageReferences()) {
            pages.putIfAbsent(page.pageReferenceId(), page);
            objectsBySourceSlide.computeIfAbsent(page.sourceSlide(), ignored -> new HashSet<>())
                    .addAll(page.objectIds());
        }
        for (ContractModels.TemplateComponent component : request.templateProfile().components()) {
            components.putIfAbsent(component.componentId(), component);
            for (ContractModels.ComponentSlot slot : component.slots()) {
                slots.putIfAbsent(slot.slotId(), new SlotOwner(component, slot));
            }
        }

        Map<String, ContractModels.LockedPptSlide> inputsById = new LinkedHashMap<>();
        Map<String, Integer> inputIndexes = new HashMap<>();
        for (int index = 0; index < request.specification().slides().size(); index++) {
            ContractModels.LockedPptSlide slide = request.specification().slides().get(index);
            inputsById.put(slide.slideId(), slide);
            inputIndexes.put(slide.slideId(), index);
        }

        Set<String> seenSlideIds = new HashSet<>();
        int previousIndex = -1;
        int commonCount = Math.min(request.specification().slides().size(), plan.slides().size());
        for (int index = 0; index < commonCount; index++) {
            ContractModels.LockedPptSlide expectedSlide = request.specification().slides().get(index);
            CompositionModels.ComposedSlidePlan actualSlide = plan.slides().get(index);
            if (!expectedSlide.slideId().equals(actualSlide.slideId())
                    || expectedSlide.pageNumber() != actualSlide.pageNumber()) {
                diagnostics.add(error(expectedSlide.slideId(), expectedSlide.pageNumber(),
                        null, null, null, null, "slideSequence", "positionMismatch"));
            }
        }
        for (CompositionModels.ComposedSlidePlan slidePlan : plan.slides()) {
            ContractModels.LockedPptSlide inputSlide = inputsById.get(slidePlan.slideId());
            Integer inputIndex = inputIndexes.get(slidePlan.slideId());
            if (inputSlide == null || inputIndex == null
                    || !seenSlideIds.add(slidePlan.slideId())
                    || inputIndex <= previousIndex
                    || inputSlide.pageNumber() != slidePlan.pageNumber()) {
                diagnostics.add(error(
                        inputSlide == null ? slidePlan.slideId() : inputSlide.slideId(),
                        inputSlide == null ? slidePlan.pageNumber() : inputSlide.pageNumber(),
                        null, null, null, null, "slideSequence", "mismatch"));
                continue;
            }
            previousIndex = inputIndex;
            validateSlide(request.templateProfile(), inputSlide, slidePlan,
                    pages, components, slots, objectsBySourceSlide,
                    request.manifestEntriesByRequirementId(), partialContext, diagnostics);
        }
        for (ContractModels.LockedPptSlide inputSlide : request.specification().slides()) {
            if (!seenSlideIds.contains(inputSlide.slideId())) {
                diagnostics.add(error(inputSlide.slideId(), inputSlide.pageNumber(),
                        null, null, null, null, "slideSequence", "missing"));
            }
        }
    }

    private void validateSlide(
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.LockedPptSlide inputSlide,
            CompositionModels.ComposedSlidePlan slidePlan,
            Map<String, ContractModels.TemplatePageReference> pages,
            Map<String, ContractModels.TemplateComponent> components,
            Map<String, SlotOwner> slots,
            Map<Integer, Set<String>> objectsBySourceSlide,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            PartialPlanContext partialContext,
            List<ContractModels.Diagnostic> diagnostics) {
        CompositionModels.ResolvedLayoutPlan layout = slidePlan.layout();
        if (layout == null
                || !inputSlide.slideId().equals(layout.slideId())
                || inputSlide.pageNumber() != layout.pageNumber()
                || !profile.pageSize().equals(layout.pageSize())) {
            diagnostics.add(error(inputSlide.slideId(), inputSlide.pageNumber(),
                    null, null, null, null, "layout", "mismatch"));
            return;
        }

        CompositionModels.TemplatePageSelection selection = layout.templatePage();
        ContractModels.TemplatePageReference page = selection == null
                ? null : pages.get(selection.pageReferenceId());
        if (page == null
                || page.sourceSlide() != layout.templatePage().sourceSlide()
                || !page.semanticRole().equals(inputSlide.semanticLayout().primaryRole())
                || !page.semanticRole().equals(selection.semanticRole())
                || page.pageReferenceId() == null || page.pageReferenceId().isBlank()
                || page.objectIds() == null || page.objectIds().isEmpty()
                || selection.selectionBasis() != EXACT_SEMANTIC_ROLE_STABLE_ORDER) {
            diagnostics.add(error(inputSlide.slideId(), inputSlide.pageNumber(),
                    null, null, null, null, "templatePage", "invalid"));
            return;
        }

        validatePlacements(profile, inputSlide, layout, components, slots,
                manifestEntries, partialContext, diagnostics);
        validateOperations(profile, inputSlide, slidePlan, page, components, slots,
                objectsBySourceSlide, manifestEntries, partialContext, diagnostics);
    }

    private void validatePlacements(
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.LockedPptSlide slide,
            CompositionModels.ResolvedLayoutPlan layout,
            Map<String, ContractModels.TemplateComponent> components,
            Map<String, SlotOwner> slots,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            PartialPlanContext partialContext,
            List<ContractModels.Diagnostic> diagnostics) {
        ContractModels.Bounds expectedSafeArea = new ContractModels.Bounds(
                profile.spatialProfile().safeMarginLeftEmu(),
                profile.spatialProfile().safeMarginTopEmu(),
                profile.pageSize().widthEmu() - profile.spatialProfile().safeMarginLeftEmu()
                        - profile.spatialProfile().safeMarginRightEmu(),
                profile.pageSize().heightEmu() - profile.spatialProfile().safeMarginTopEmu()
                        - profile.spatialProfile().safeMarginBottomEmu());
        if (!expectedSafeArea.equals(layout.safeArea())) {
            diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                    null, null, null, null, "safeArea", "mismatch"));
        }

        Set<String> componentPlacementIds = new HashSet<>();
        List<String> componentOrder = layout.componentPlacements().stream()
                .map(CompositionModels.ResolvedComponentPlacement::componentId)
                .toList();
        if (!componentOrder.equals(componentOrder.stream().sorted().toList())) {
            diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                    null, null, null, null, "componentPlacementOrder", "mismatch"));
        }
        for (CompositionModels.ResolvedComponentPlacement placement : layout.componentPlacements()) {
            ContractModels.TemplateComponent component = components.get(placement.componentId());
            List<CompositionModels.StableNativeObjectReference> expectedReferences = component == null
                    ? List.of()
                    : component.shapeRefs().stream()
                    .map(shape -> stableReference(
                            profile, component.sourceSlide(), shape.objectId(), shape.objectType()))
                    .toList();
            if (!componentPlacementIds.add(placement.componentId())
                    || component == null
                    || !expectedReferences.equals(placement.nativeObjectReferences())
                    || component.transformConstraint() != placement.allowedTransform()) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                        null, null, placement.componentId(), null,
                        "componentPlacement", "invalid"));
            }
        }
        validateComponentSelections(slide, layout, components, slots, manifestEntries,
                partialContext, componentPlacementIds, diagnostics);

        Set<String> placementIds = new HashSet<>();
        Map<String, Integer> bindingCounts = new HashMap<>();
        Map<String, Integer> regionUsage = new HashMap<>();
        List<String> expectedPlacementOrder = new ArrayList<>();
        slide.contentBlocks().stream()
                .filter(block -> !partialContext.incompleteBlockIds().contains(block.blockId()))
                .forEach(block -> expectedPlacementOrder.add(bindingKey(TEXT, block.blockId())));
        slide.assetRequirements().stream()
                .filter(asset -> approvedEntry(manifestEntries, asset.assetId()) != null)
                .filter(asset -> !partialContext.incompleteAssetRequirementIds().contains(asset.assetId()))
                .forEach(asset -> expectedPlacementOrder.add(bindingKey(ASSET, asset.assetId())));
        List<String> actualPlacementOrder = layout.slotPlacements().stream()
                .map(placement -> bindingKey(placement.bindingKind(), placement.bindingId()))
                .toList();
        if (!expectedPlacementOrder.equals(actualPlacementOrder)) {
            diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                    null, null, null, null, "slotPlacementOrder", "mismatch"));
        }
        for (CompositionModels.SlotPlacement placement : layout.slotPlacements()) {
            String blockId = placement.bindingKind() == TEXT ? placement.bindingId() : null;
            String assetId = placement.bindingKind() == ASSET ? placement.bindingId() : null;
            SlotOwner owner = slots.get(placement.slotId());
            String expectedPlacementId = owner == null ? null : idFactory.placementId(
                    slide.slideId(), placement.bindingKind(), placement.bindingId(),
                    placement.componentId(), placement.slotId());
            ContractModels.Bounds expectedBounds = owner == null ? null : variantCompiler.boundsFor(
                    slide, profile, owner.slot(), placement.bindingKind(), placement.bindingId(), layout.safeArea());
            boolean validReference = owner != null
                    && owner.component().componentId().equals(placement.componentId())
                    && componentPlacementIds.contains(placement.componentId())
                    && expectedPlacementId.equals(placement.placementId())
                    && owner.slot().semanticRole().equals(placement.semanticRole())
                    && expectedBounds != null && expectedBounds.equals(placement.bounds())
                    && slide.semanticLayout().requestedTransform() == placement.requestedTransform()
                    && owner.component().transformConstraint() == placement.allowedTransform();
            if (!placementIds.add(placement.placementId()) || !validReference) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(), blockId, assetId,
                        placement.componentId(), placement.slotId(), "slotPlacement", "invalid"));
            }
            bindingCounts.merge(bindingKey(placement.bindingKind(), placement.bindingId()), 1, Integer::sum);
            validatePlacementRegionAndBounds(profile, slide, layout.safeArea(), placement,
                    allocateExpectedRegion(slide, placement.semanticRole(), regionUsage), diagnostics);
        }

        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            int expectedCount = partialContext.incompleteBlockIds().contains(block.blockId()) ? 0 : 1;
            if (bindingCounts.getOrDefault(bindingKey(TEXT, block.blockId()), 0) != expectedCount) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(), block.blockId(), null,
                        null, null, "blockPlacement",
                        expectedCount == 0 ? "incompleteBindingWasPlaced" : "notExactlyOnce"));
            }
        }
        for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
            int expectedCount = approvedEntry(manifestEntries, asset.assetId()) == null
                    || partialContext.incompleteAssetRequirementIds().contains(asset.assetId()) ? 0 : 1;
            if (bindingCounts.getOrDefault(bindingKey(ASSET, asset.assetId()), 0) != expectedCount) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(), null, asset.assetId(),
                        null, null, "assetPlacement", expectedCount == 0 ? "omissionWasPlaced" : "notExactlyOnce"));
            }
        }
        for (String bindingKey : bindingCounts.keySet()) {
            boolean known = slide.contentBlocks().stream()
                    .anyMatch(block -> bindingKey.equals(bindingKey(TEXT, block.blockId())))
                    || slide.assetRequirements().stream()
                    .filter(asset -> approvedEntry(manifestEntries, asset.assetId()) != null)
                    .anyMatch(asset -> bindingKey.equals(bindingKey(ASSET, asset.assetId())));
            if (!known) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                        null, null, null, null, "binding", "dangling"));
            }
        }
    }

    private void validateComponentSelections(
            ContractModels.LockedPptSlide slide,
            CompositionModels.ResolvedLayoutPlan layout,
            Map<String, ContractModels.TemplateComponent> components,
            Map<String, SlotOwner> slots,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            PartialPlanContext partialContext,
            Set<String> componentPlacementIds,
            List<ContractModels.Diagnostic> diagnostics) {
        Set<String> selectionBindings = new HashSet<>();
        int previousOrder = -1;
        for (CompositionModels.ComponentSelection selection : layout.componentSelections()) {
            String key = bindingKey(selection.bindingKind(), selection.bindingId());
            int order = bindingOrder(slide, manifestEntries, selection.bindingKind(), selection.bindingId());
            SlotOwner owner = slots.get(selection.selectedSlotId());
            boolean knownBinding = order >= 0;
            boolean valid = selection.selectionBasis()
                    == ContractTypes.ComponentSelectionBasis.PROFILE_V1_CONFIDENCE_FALLBACK_TOTAL_ORDER
                    && selectionBindings.add(key)
                    && knownBinding
                    && order > previousOrder
                    && owner != null
                    && owner.component().componentId().equals(selection.selectedComponentId())
                    && componentPlacementIds.contains(selection.selectedComponentId());
            if (!valid) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                        selection.bindingKind() == TEXT ? selection.bindingId() : null,
                        selection.bindingKind() == ASSET ? selection.bindingId() : null,
                        selection.selectedComponentId(), selection.selectedSlotId(),
                        "componentSelection", "invalid"));
            }
            if (knownBinding) {
                previousOrder = order;
            }
        }
        for (CompositionModels.SlotPlacement placement : layout.slotPlacements()) {
            if (!selectionBindings.contains(bindingKey(placement.bindingKind(), placement.bindingId()))) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                        placement.bindingKind() == TEXT ? placement.bindingId() : null,
                        placement.bindingKind() == ASSET ? placement.bindingId() : null,
                        placement.componentId(), placement.slotId(),
                        "componentSelection", "missing"));
            }
        }
        for (CompositionModels.ComponentSelection selection : layout.componentSelections()) {
            boolean incomplete = selection.bindingKind() == TEXT
                    ? partialContext.incompleteBlockIds().contains(selection.bindingId())
                    : partialContext.incompleteAssetRequirementIds().contains(selection.bindingId());
            boolean hasPlacement = layout.slotPlacements().stream().anyMatch(placement ->
                    placement.bindingKind() == selection.bindingKind()
                            && placement.bindingId().equals(selection.bindingId())
                            && placement.componentId().equals(selection.selectedComponentId())
                            && placement.slotId().equals(selection.selectedSlotId()));
            if (!hasPlacement && !incomplete) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                        selection.bindingKind() == TEXT ? selection.bindingId() : null,
                        selection.bindingKind() == ASSET ? selection.bindingId() : null,
                        selection.selectedComponentId(), selection.selectedSlotId(),
                        "componentSelection", "placementMissing"));
            }
        }
    }

    private int bindingOrder(
            ContractModels.LockedPptSlide slide,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            ContractTypes.SlotBindingKind kind,
            String bindingId) {
        if (kind == TEXT) {
            for (int index = 0; index < slide.contentBlocks().size(); index++) {
                if (slide.contentBlocks().get(index).blockId().equals(bindingId)) {
                    return index;
                }
            }
            return -1;
        }
        for (int index = 0; index < slide.assetRequirements().size(); index++) {
            ContractModels.LockedPptAssetReference asset = slide.assetRequirements().get(index);
            if (asset.assetId().equals(bindingId)
                    && approvedEntry(manifestEntries, bindingId) != null) {
                return slide.contentBlocks().size() + index;
            }
        }
        return -1;
    }

    private void validatePlacementRegionAndBounds(
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.LockedPptSlide slide,
            ContractModels.Bounds safeArea,
            CompositionModels.SlotPlacement placement,
            ExpectedRegion expectedRegion,
            List<ContractModels.Diagnostic> diagnostics) {
        String expectedRegionId = expectedRegion.region() == null
                ? null : expectedRegion.region().regionId();
        if (expectedRegion.capacityExceeded()
                || !Objects.equals(expectedRegionId, placement.regionId())) {
            diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                    placement.bindingKind() == TEXT ? placement.bindingId() : null,
                    placement.bindingKind() == ASSET ? placement.bindingId() : null,
                    placement.componentId(), placement.slotId(), "region",
                    expectedRegion.capacityExceeded() ? "capacityExceeded" : "deterministicAssignmentMismatch"));
        }
        boolean fullBleed = expectedRegion.region() != null
                && expectedRegion.region().preferredPosition() == FULL_BLEED;
        if (!insidePage(placement.bounds(), profile.pageSize())
                || (!fullBleed && !inside(placement.bounds(), safeArea))) {
            diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                    placement.bindingKind() == TEXT ? placement.bindingId() : null,
                    placement.bindingKind() == ASSET ? placement.bindingId() : null,
                    placement.componentId(), placement.slotId(), "bounds", "invalid"));
        }
    }

    private ExpectedRegion allocateExpectedRegion(
            ContractModels.LockedPptSlide slide,
            String semanticRole,
            Map<String, Integer> usage) {
        boolean hasMatchingRegion = false;
        for (ContractModels.SemanticRegion region : slide.semanticLayout().regions()) {
            if (!region.semanticRole().equals(semanticRole)) {
                continue;
            }
            hasMatchingRegion = true;
            int used = usage.getOrDefault(region.regionId(), 0);
            if (used < region.maxItems()) {
                usage.put(region.regionId(), used + 1);
                return new ExpectedRegion(region, false);
            }
        }
        return new ExpectedRegion(null, hasMatchingRegion);
    }

    private void validateOperations(
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.LockedPptSlide slide,
            CompositionModels.ComposedSlidePlan slidePlan,
            ContractModels.TemplatePageReference page,
            Map<String, ContractModels.TemplateComponent> components,
            Map<String, SlotOwner> slots,
            Map<Integer, Set<String>> objectsBySourceSlide,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            PartialPlanContext partialContext,
            List<ContractModels.Diagnostic> diagnostics) {
        List<String> expectedOrder = expectedOperationOrder(
                profile, slide, slidePlan.layout(), page, components, manifestEntries, partialContext);
        List<String> actualOrder = slidePlan.operations().stream()
                .map(CompositionModels.CompositionOperation::operationId).toList();
        if (!expectedOrder.equals(actualOrder)) {
            diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                    null, null, null, null, "operationOrder", "mismatch"));
        }

        Set<String> operationIds = new HashSet<>();
        Map<String, Integer> blockCounts = new HashMap<>();
        Map<String, Integer> assetCounts = new HashMap<>();
        for (CompositionModels.CompositionOperation operation : slidePlan.operations()) {
            if (!operationIds.add(operation.operationId())) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                        operation.blockId(), operation.assetRequirementId(), operation.componentId(), operation.slotId(),
                        "operationId", "duplicate"));
            }
            if (operation.operationType() == PRESERVE_BASE_OBJECT) {
                CompositionModels.StableNativeObjectReference reference =
                        operation.nativeObjectReference();
                String expectedId = reference == null ? null : idFactory.operationId(
                        PRESERVE_BASE_OBJECT, slide.slideId(), page.pageReferenceId(),
                        reference.referenceVersion(), reference.templateId(),
                        Integer.toString(reference.templateVersion()), reference.scope().name(),
                        Integer.toString(reference.sourceSlide()), reference.objectId(),
                        reference.objectType().name());
                if (expectedId == null
                        || !expectedId.equals(operation.operationId())
                        || !ContractTypes.STABLE_NATIVE_OBJECT_REFERENCE_V1.equals(reference.referenceVersion())
                        || !reference.templateId().equals(profile.templateId())
                        || reference.templateVersion() != profile.templateVersion()
                        || reference.scope() != ContractTypes.NativeObjectScope.SLIDE
                        || reference.sourceSlide() != page.sourceSlide()
                        || !page.pageReferenceId().equals(operation.pageReferenceId())
                        || !page.objectIds().contains(reference.objectId())
                        || reference.objectType() != ContractTypes.ObjectType.UNKNOWN
                        || operation.componentId() != null
                        || operation.componentObjectAction() != null
                        || operation.slotId() != null
                        || operation.blockId() != null
                        || operation.contentSha256() != null
                        || operation.assetRequirementId() != null
                        || operation.approvedAssetId() != null
                        || operation.assetType() != null
                        || operation.bounds() != null
                        || operation.requestedTransform() != null
                        || operation.allowedTransform() != null) {
                    diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                            null, null, null, null, "baseObject", "invalid"));
                }
            } else if (operation.operationType() == USE_OR_CLONE_COMPONENT_OBJECT) {
                ContractModels.TemplateComponent component = components.get(operation.componentId());
                CompositionModels.StableNativeObjectReference reference = operation.nativeObjectReference();
                boolean objectKnown = component != null && reference != null
                        && ContractTypes.STABLE_NATIVE_OBJECT_REFERENCE_V1.equals(reference.referenceVersion())
                        && reference.templateId().equals(profile.templateId())
                        && reference.templateVersion() == profile.templateVersion()
                        && reference.scope() == ContractTypes.NativeObjectScope.SLIDE
                        && component.sourceSlide() == reference.sourceSlide()
                        && component.shapeRefs().stream().anyMatch(shape ->
                        shape.objectId().equals(reference.objectId())
                                && shape.objectType() == reference.objectType())
                        && objectsBySourceSlide.getOrDefault(reference.sourceSlide(), Set.of())
                        .contains(reference.objectId());
                ContractTypes.ComponentObjectAction expectedAction = component != null
                        && reference != null && reference.sourceSlide() == page.sourceSlide()
                        ? USE_EXISTING_OBJECT : CLONE_FROM_SOURCE;
                String expectedId = component == null || reference == null ? null : idFactory.operationId(
                        USE_OR_CLONE_COMPONENT_OBJECT, slide.slideId(), component.componentId(),
                        reference.referenceVersion(), reference.templateId(),
                        Integer.toString(reference.templateVersion()), reference.scope().name(),
                        Integer.toString(reference.sourceSlide()), reference.objectId(),
                        reference.objectType().name(), expectedAction.name());
                if (expectedId == null
                        || !expectedId.equals(operation.operationId())
                        || !objectKnown
                        || operation.componentObjectAction() != expectedAction
                        || operation.pageReferenceId() != null
                        || operation.slotId() != null
                        || operation.blockId() != null
                        || operation.contentSha256() != null
                        || operation.assetRequirementId() != null
                        || operation.approvedAssetId() != null
                        || operation.assetType() != null
                        || !validOptionalComponentTransform(operation, slide, component)) {
                    diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                            null, null, operation.componentId(), null, "componentObject", "invalid"));
                }
            } else if (operation.operationType() == FILL_TEXT_SLOT) {
                ContractModels.LockedPptContentBlock block = slide.contentBlocks().stream()
                        .filter(item -> item.blockId().equals(operation.blockId()))
                        .findFirst().orElse(null);
                SlotOwner owner = slots.get(operation.slotId());
                CompositionModels.SlotPlacement expectedPlacement =
                        findPlacement(slidePlan.layout(), TEXT, operation.blockId()).orElse(null);
                String expectedId = expectedPlacement == null || block == null ? null : idFactory.operationId(
                        FILL_TEXT_SLOT, slide.slideId(), block.blockId(),
                        expectedPlacement.componentId(), expectedPlacement.slotId());
                boolean valid = expectedId != null
                        && expectedId.equals(operation.operationId())
                        && block != null && owner != null && expectedPlacement != null
                        && owner.component().componentId().equals(operation.componentId())
                        && expectedPlacement.componentId().equals(operation.componentId())
                        && expectedPlacement.slotId().equals(operation.slotId())
                        && checksumService.sha256Utf8(block.content()).equals(operation.contentSha256())
                        && expectedPlacement.bounds().equals(operation.bounds())
                        && expectedPlacement.requestedTransform() == operation.requestedTransform()
                        && expectedPlacement.allowedTransform() == operation.allowedTransform()
                        && operation.pageReferenceId() == null
                        && operation.nativeObjectReference() == null
                        && operation.componentObjectAction() == null
                        && operation.assetRequirementId() == null
                        && operation.approvedAssetId() == null
                        && operation.assetType() == null;
                if (!valid) {
                    diagnostics.add(error(slide.slideId(), slide.pageNumber(), operation.blockId(), null,
                            operation.componentId(), operation.slotId(), "textFill", "invalid"));
                }
                if (operation.blockId() != null) {
                    blockCounts.merge(operation.blockId(), 1, Integer::sum);
                }
            } else if (operation.operationType() == FILL_ASSET_SLOT) {
                ContractModels.LockedPptAssetReference asset = slide.assetRequirements().stream()
                        .filter(item -> item.assetId().equals(operation.assetRequirementId()))
                        .findFirst().orElse(null);
                CompositionModels.ApprovedAssetManifestEntry manifestEntry =
                        approvedEntry(manifestEntries, operation.assetRequirementId());
                SlotOwner owner = slots.get(operation.slotId());
                CompositionModels.SlotPlacement expectedPlacement =
                        findPlacement(slidePlan.layout(), ASSET, operation.assetRequirementId()).orElse(null);
                String expectedId = expectedPlacement == null || asset == null || manifestEntry == null
                        ? null : idFactory.operationId(
                        FILL_ASSET_SLOT, slide.slideId(), asset.assetId(), manifestEntry.approvedAssetId(),
                        expectedPlacement.componentId(), expectedPlacement.slotId());
                boolean valid = expectedId != null
                        && expectedId.equals(operation.operationId())
                        && asset != null && owner != null && expectedPlacement != null
                        && owner.component().componentId().equals(operation.componentId())
                        && expectedPlacement.componentId().equals(operation.componentId())
                        && expectedPlacement.slotId().equals(operation.slotId())
                        && supportedAsset(asset.assetType())
                        && asset.assetType() == manifestEntry.assetType()
                        && manifestEntry.approvedAssetId().equals(operation.approvedAssetId())
                        && manifestEntry.assetType() == operation.assetType()
                        && manifestEntry.contentSha256().equals(operation.contentSha256())
                        && expectedPlacement.bounds().equals(operation.bounds())
                        && expectedPlacement.requestedTransform() == operation.requestedTransform()
                        && expectedPlacement.allowedTransform() == operation.allowedTransform()
                        && operation.pageReferenceId() == null
                        && operation.nativeObjectReference() == null
                        && operation.componentObjectAction() == null
                        && operation.blockId() == null
                        ;
                if (!valid) {
                    diagnostics.add(error(slide.slideId(), slide.pageNumber(), null, operation.assetRequirementId(),
                            operation.componentId(), operation.slotId(), "assetFill", "invalid"));
                }
                if (operation.assetRequirementId() != null) {
                    assetCounts.merge(operation.assetRequirementId(), 1, Integer::sum);
                }
            } else {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(),
                        operation.blockId(), operation.assetRequirementId(), operation.componentId(), operation.slotId(),
                        "operationType", "invalid"));
            }
        }
        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            int expectedCount = partialContext.incompleteBlockIds().contains(block.blockId()) ? 0 : 1;
            if (blockCounts.getOrDefault(block.blockId(), 0) != expectedCount) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(), block.blockId(), null,
                        null, null, "textFill",
                        expectedCount == 0 ? "incompleteBindingWasFilled" : "notExactlyOnce"));
            }
        }
        for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
            int expectedCount = approvedEntry(manifestEntries, asset.assetId()) == null
                    || partialContext.incompleteAssetRequirementIds().contains(asset.assetId()) ? 0 : 1;
            if (assetCounts.getOrDefault(asset.assetId(), 0) != expectedCount) {
                diagnostics.add(error(slide.slideId(), slide.pageNumber(), null, asset.assetId(),
                        null, null, "assetFill", expectedCount == 0 ? "omissionWasFilled" : "notExactlyOnce"));
            }
        }
    }

    private List<String> expectedOperationOrder(
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.LockedPptSlide slide,
            CompositionModels.ResolvedLayoutPlan layout,
            ContractModels.TemplatePageReference page,
            Map<String, ContractModels.TemplateComponent> components,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            PartialPlanContext partialContext) {
        List<String> expected = new ArrayList<>();
        for (String objectId : page.objectIds()) {
            CompositionModels.StableNativeObjectReference reference = stableReference(
                    profile, page.sourceSlide(), objectId, ContractTypes.ObjectType.UNKNOWN);
            expected.add(idFactory.operationId(
                    PRESERVE_BASE_OBJECT, slide.slideId(), page.pageReferenceId(),
                    reference.referenceVersion(), reference.templateId(),
                    Integer.toString(reference.templateVersion()), reference.scope().name(),
                    Integer.toString(reference.sourceSlide()), reference.objectId(),
                    reference.objectType().name()));
        }
        layout.componentPlacements().stream()
                .map(CompositionModels.ResolvedComponentPlacement::componentId)
                .sorted()
                .map(components::get)
                .filter(java.util.Objects::nonNull)
                .forEach(component -> {
                    for (CompositionModels.StableNativeObjectReference reference
                            : layout.componentPlacements().stream()
                            .filter(item -> item.componentId().equals(component.componentId()))
                            .findFirst().orElseThrow().nativeObjectReferences()) {
                        ContractTypes.ComponentObjectAction action =
                                reference.sourceSlide() == page.sourceSlide()
                                ? USE_EXISTING_OBJECT : CLONE_FROM_SOURCE;
                        expected.add(idFactory.operationId(USE_OR_CLONE_COMPONENT_OBJECT, slide.slideId(),
                                component.componentId(), reference.referenceVersion(),
                                reference.templateId(), Integer.toString(reference.templateVersion()),
                                reference.scope().name(), Integer.toString(reference.sourceSlide()),
                                reference.objectId(), reference.objectType().name(), action.name()));
                    }
                });
        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            if (!partialContext.incompleteBlockIds().contains(block.blockId())) {
                findPlacement(layout, TEXT, block.blockId()).ifPresent(placement ->
                        expected.add(idFactory.operationId(FILL_TEXT_SLOT, slide.slideId(), block.blockId(),
                                placement.componentId(), placement.slotId())));
            }
        }
        for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
            CompositionModels.ApprovedAssetManifestEntry manifestEntry =
                    approvedEntry(manifestEntries, asset.assetId());
            if (manifestEntry != null
                    && !partialContext.incompleteAssetRequirementIds().contains(asset.assetId())) {
                findPlacement(layout, ASSET, asset.assetId()).ifPresent(placement ->
                        expected.add(idFactory.operationId(
                                FILL_ASSET_SLOT, slide.slideId(), asset.assetId(),
                                manifestEntry.approvedAssetId(), placement.componentId(), placement.slotId())));
            }
        }
        return expected;
    }

    private CompositionModels.ApprovedAssetManifestEntry approvedEntry(
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            String assetRequirementId) {
        CompositionModels.ApprovedAssetManifestEntry entry = manifestEntries.get(assetRequirementId);
        return entry != null && entry.resolution() == APPROVED_ASSET ? entry : null;
    }

    private CompositionModels.StableNativeObjectReference stableReference(
            ContractModels.ConfirmedTemplateProfile profile,
            int sourceSlide,
            String objectId,
            ContractTypes.ObjectType objectType) {
        return new CompositionModels.StableNativeObjectReference(
                ContractTypes.STABLE_NATIVE_OBJECT_REFERENCE_V1,
                profile.templateId(), profile.templateVersion(),
                ContractTypes.NativeObjectScope.SLIDE,
                sourceSlide, objectId, objectType);
    }

    private java.util.Optional<CompositionModels.SlotPlacement> findPlacement(
            CompositionModels.ResolvedLayoutPlan layout,
            ContractTypes.SlotBindingKind kind,
            String bindingId) {
        List<CompositionModels.SlotPlacement> matches = layout.slotPlacements().stream()
                .filter(item -> item.bindingKind() == kind && item.bindingId().equals(bindingId))
                .toList();
        return matches.size() == 1 ? java.util.Optional.of(matches.get(0)) : java.util.Optional.empty();
    }

    private boolean validOptionalComponentTransform(
            CompositionModels.CompositionOperation operation,
            ContractModels.LockedPptSlide slide,
            ContractModels.TemplateComponent component) {
        boolean absent = operation.bounds() == null
                && operation.requestedTransform() == null
                && operation.allowedTransform() == null;
        boolean complete = operation.bounds() != null
                && operation.requestedTransform() != null
                && operation.allowedTransform() != null;
        return absent || (complete
                && operation.requestedTransform() == slide.semanticLayout().requestedTransform()
                && operation.allowedTransform() == component.transformConstraint()
                && operation.bounds().widthEmu() > 0
                && operation.bounds().heightEmu() > 0);
    }

    private boolean supportedAsset(ContractTypes.AssetType assetType) {
        return assetType == ContractTypes.AssetType.IMAGE
                || assetType == ContractTypes.AssetType.CHART
                || assetType == ContractTypes.AssetType.TABLE;
    }

    private String bindingKey(ContractTypes.SlotBindingKind kind, String bindingId) {
        return kind.name() + "\u0000" + bindingId;
    }

    private boolean insidePage(ContractModels.Bounds bounds, ContractModels.PageSize page) {
        return bounds != null
                && bounds.leftEmu() >= 0
                && bounds.topEmu() >= 0
                && bounds.widthEmu() > 0
                && bounds.heightEmu() > 0
                && (long) bounds.leftEmu() + bounds.widthEmu() <= page.widthEmu()
                && (long) bounds.topEmu() + bounds.heightEmu() <= page.heightEmu();
    }

    private boolean inside(ContractModels.Bounds inner, ContractModels.Bounds outer) {
        return inner != null && outer != null
                && inner.leftEmu() >= outer.leftEmu()
                && inner.topEmu() >= outer.topEmu()
                && (long) inner.leftEmu() + inner.widthEmu()
                <= (long) outer.leftEmu() + outer.widthEmu()
                && (long) inner.topEmu() + inner.heightEmu()
                <= (long) outer.topEmu() + outer.heightEmu();
    }

    private ContractModels.Diagnostic error(
            String slideId,
            Integer pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            String reference,
            String reason) {
        return DiagnosticFactory.stageError(
                PLAN_VALIDATOR,
                COMPOSITION_REFERENCE_INVALID,
                "plan.referenceInvalid",
                slideId, pageNumber, blockId, assetId, componentId, slotId,
                Map.of("reference", reference, "reason", reason));
    }

    public record PartialPlanContext(
            Set<String> incompleteBlockIds,
            Set<String> incompleteAssetRequirementIds) {
        public PartialPlanContext {
            incompleteBlockIds = Set.copyOf(incompleteBlockIds);
            incompleteAssetRequirementIds = Set.copyOf(incompleteAssetRequirementIds);
        }

        public static PartialPlanContext complete() {
            return new PartialPlanContext(Set.of(), Set.of());
        }
    }

    private record SlotOwner(
            ContractModels.TemplateComponent component,
            ContractModels.ComponentSlot slot) {
    }

    private record ExpectedRegion(
            ContractModels.SemanticRegion region,
            boolean capacityExceeded) {
    }
}
