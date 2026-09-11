package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.composition.CompositionPlanValidator;
import com.auvdidao.a12.pptengine.composition.SlideComposer;
import com.auvdidao.a12.pptengine.composition.StablePlanIdFactory;
import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.layout.LayoutResolver;
import com.auvdidao.a12.pptengine.layout.TemplatePageResolver;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositionPlanValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ChecksumService checksumService = new ChecksumService(objectMapper);
    private final StablePlanIdFactory idFactory = new StablePlanIdFactory(checksumService);
    private final CompositionPlanValidator validator =
            new CompositionPlanValidator(checksumService, idFactory, objectMapper);

    @Test
    void validPlanPassesIndependentGate() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        assertThat(validator.validate(CompositionTestSupport.validatedPackage(request), validPlan(request))).isEmpty();
    }

    @Test
    void validatorRejectsShortLongMissingDuplicateAndOutOfOrderSlidePlans() {
        ContractModels.LockedPptSlide first = ContractFixtures.slide();
        ContractModels.LockedPptSlide second = new ContractModels.LockedPptSlide(
                "slide-002", 2, first.title(), first.teachingGoal(),
                List.of(new ContractModels.LockedPptContentBlock(
                        "block-002", com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY,
                        "第二页锁定内容", com.auvdidao.a12.pptengine.contract.ContractTypes.ContentSourceType.MATERIAL,
                        "material-002", true)),
                first.semanticLayout(), List.of(), first.provenance(), first.notes());
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(
                objectMapper, "req-validator-pages", List.of(first, second), ContractFixtures.profile());
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);

        assertSlideSequenceDiagnostic(request, withSlides(valid, List.of(valid.slides().get(0))), "slideCount");
        assertSlideSequenceDiagnostic(request, withSlides(valid,
                List.of(valid.slides().get(0), valid.slides().get(1), valid.slides().get(0))), "slideCount");
        assertSlideSequenceDiagnostic(request, withSlides(valid,
                List.of(valid.slides().get(0), valid.slides().get(0))), "slideSequence");
        assertSlideSequenceDiagnostic(request, withSlides(valid,
                List.of(valid.slides().get(1), valid.slides().get(0))), "slideSequence");
        CompositionModels.ComposedSlidePlan wrongPage = new CompositionModels.ComposedSlidePlan(
                valid.slides().get(0).slideId(), 99, valid.slides().get(0).layout(),
                valid.slides().get(0).operations());
        assertSlideSequenceDiagnostic(request, withSlides(valid,
                List.of(wrongPage, valid.slides().get(1))), "slideSequence");
    }

    @Test
    void checksumMismatchIsRejectedWithoutRepair() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        CompositionModels.ComposedPresentationPlan invalid = new CompositionModels.ComposedPresentationPlan(
                valid.planContractVersion(), valid.requestReference(), valid.generationJobReference(),
                valid.specificationReference(),
                valid.templateProfileReference(), valid.approvedAssetManifestReference(),
                valid.textFitBoundary(),
                valid.originalSlideCount(), valid.slides(),
                "0000000000000000000000000000000000000000000000000000000000000000");

        assertThat(validator.validate(CompositionTestSupport.validatedPackage(request), invalid))
                .extracting(ContractModels.Diagnostic::code)
                .contains("PLAN_CHECKSUM_MISMATCH");
    }

    @Test
    void missingDuplicateAndDanglingOperationsAreRejected() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        List<CompositionModels.CompositionOperation> base = valid.slides().get(0).operations();

        List<CompositionModels.CompositionOperation> missing = new ArrayList<>(base);
        missing.removeIf(operation -> operation.operationType().name().equals("FILL_TEXT_SLOT"));
        assertInvalidReference(request, withOperations(valid, missing));

        List<CompositionModels.CompositionOperation> duplicate = new ArrayList<>(base);
        duplicate.add(base.stream()
                .filter(operation -> operation.operationType().name().equals("FILL_TEXT_SLOT"))
                .findFirst().orElseThrow());
        assertInvalidReference(request, withOperations(valid, duplicate));

        List<CompositionModels.CompositionOperation> dangling = new ArrayList<>(base);
        int componentIndex = indexOfType(dangling, "USE_OR_CLONE_COMPONENT_OBJECT");
        CompositionModels.CompositionOperation operation = dangling.get(componentIndex);
        CompositionModels.StableNativeObjectReference reference = operation.nativeObjectReference();
        dangling.set(componentIndex, new CompositionModels.CompositionOperation(
                operation.operationId(), operation.operationType(), operation.pageReferenceId(),
                new CompositionModels.StableNativeObjectReference(
                        reference.referenceVersion(), reference.templateId(), reference.templateVersion(),
                        reference.scope(), reference.sourceSlide(), "object-missing", reference.objectType()),
                operation.componentId(), operation.componentObjectAction(), operation.slotId(),
                operation.blockId(), operation.contentSha256(), operation.assetRequirementId(),
                operation.approvedAssetId(), operation.assetType(),
                operation.bounds(), operation.requestedTransform(), operation.allowedTransform()));
        assertInvalidReference(request, withOperations(valid, dangling));
    }

    @Test
    void operationReorderingIsRejectedEvenWithRecomputedChecksum() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        List<CompositionModels.CompositionOperation> reordered =
                new ArrayList<>(valid.slides().get(0).operations());
        Collections.swap(reordered, 0, 1);

        assertInvalidReference(request, withOperations(valid, reordered));
    }

    @Test
    void deterministicRegionAssignmentCannotBeRemovedWithARecomputedChecksum() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        CompositionModels.ComposedSlidePlan originalSlide = valid.slides().get(0);
        CompositionModels.ResolvedLayoutPlan originalLayout = originalSlide.layout();
        List<CompositionModels.SlotPlacement> placements = new ArrayList<>(originalLayout.slotPlacements());
        CompositionModels.SlotPlacement text = placements.get(0);
        placements.set(0, new CompositionModels.SlotPlacement(
                text.placementId(), text.componentId(), text.slotId(), text.bindingKind(),
                text.bindingId(), text.semanticRole(), null, text.bounds(),
                text.requestedTransform(), text.allowedTransform()));
        CompositionModels.ResolvedLayoutPlan changedLayout = new CompositionModels.ResolvedLayoutPlan(
                originalLayout.slideId(), originalLayout.pageNumber(), originalLayout.pageSize(),
                originalLayout.safeArea(), originalLayout.templatePage(),
                originalLayout.componentPlacements(), originalLayout.componentSelections(), placements);
        CompositionModels.ComposedSlidePlan changedSlide = new CompositionModels.ComposedSlidePlan(
                originalSlide.slideId(), originalSlide.pageNumber(), changedLayout,
                originalSlide.operations());

        assertInvalidReference(request, withSlide(valid, changedSlide));
    }

    @Test
    void missingRequiredPlacementIsRejectedEvenWhenPlanChecksumIsRecomputed() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        CompositionModels.ComposedSlidePlan originalSlide = valid.slides().get(0);
        CompositionModels.ResolvedLayoutPlan originalLayout = originalSlide.layout();
        List<CompositionModels.SlotPlacement> placements = new ArrayList<>(originalLayout.slotPlacements());
        placements.removeIf(placement -> placement.bindingKind().name().equals("TEXT"));
        CompositionModels.ResolvedLayoutPlan changedLayout = new CompositionModels.ResolvedLayoutPlan(
                originalLayout.slideId(), originalLayout.pageNumber(), originalLayout.pageSize(),
                originalLayout.safeArea(), originalLayout.templatePage(),
                originalLayout.componentPlacements(), originalLayout.componentSelections(), placements);
        CompositionModels.ComposedSlidePlan changedSlide = new CompositionModels.ComposedSlidePlan(
                originalSlide.slideId(), originalSlide.pageNumber(), changedLayout,
                originalSlide.operations());

        assertInvalidReference(request, withSlide(valid, changedSlide));
    }

    @Test
    void operationIdAndFillTransformsMustMatchTheirExactOperationFields() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        List<CompositionModels.CompositionOperation> operations =
                new ArrayList<>(valid.slides().get(0).operations());

        int firstComponentIndex = indexOfType(operations, "USE_OR_CLONE_COMPONENT_OBJECT");
        int secondComponentIndex = firstComponentIndex + 1;
        CompositionModels.CompositionOperation firstComponent = operations.get(firstComponentIndex);
        CompositionModels.CompositionOperation secondComponent = operations.get(secondComponentIndex);
        operations.set(firstComponentIndex, new CompositionModels.CompositionOperation(
                firstComponent.operationId(), secondComponent.operationType(),
                secondComponent.pageReferenceId(), secondComponent.nativeObjectReference(),
                secondComponent.componentId(), secondComponent.componentObjectAction(), secondComponent.slotId(),
                secondComponent.blockId(), secondComponent.contentSha256(),
                secondComponent.assetRequirementId(), secondComponent.approvedAssetId(),
                secondComponent.assetType(), secondComponent.bounds(), secondComponent.requestedTransform(),
                secondComponent.allowedTransform()));

        int textIndex = indexOfType(operations, "FILL_TEXT_SLOT");
        CompositionModels.CompositionOperation text = operations.get(textIndex);
        operations.set(textIndex, new CompositionModels.CompositionOperation(
                text.operationId(), text.operationType(), text.pageReferenceId(),
                text.nativeObjectReference(), text.componentId(), text.componentObjectAction(),
                text.slotId(), text.blockId(), text.contentSha256(), text.assetRequirementId(),
                text.approvedAssetId(), text.assetType(),
                text.bounds(), text.requestedTransform(),
                com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.FIXED));

        assertInvalidReference(request, withOperations(valid, operations));
    }

    @Test
    void stableNativeReferenceFieldChangeChangesChecksumAndCannotPassValidation() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        List<CompositionModels.CompositionOperation> operations =
                new ArrayList<>(valid.slides().get(0).operations());
        int componentIndex = indexOfType(operations, "USE_OR_CLONE_COMPONENT_OBJECT");
        CompositionModels.CompositionOperation operation = operations.get(componentIndex);
        CompositionModels.StableNativeObjectReference reference = operation.nativeObjectReference();
        CompositionModels.StableNativeObjectReference changedReference =
                new CompositionModels.StableNativeObjectReference(
                        reference.referenceVersion(), reference.templateId(), reference.templateVersion() + 1,
                        reference.scope(), reference.sourceSlide(), reference.objectId(), reference.objectType());
        operations.set(componentIndex, new CompositionModels.CompositionOperation(
                operation.operationId(), operation.operationType(), operation.pageReferenceId(),
                changedReference, operation.componentId(), operation.componentObjectAction(),
                operation.slotId(), operation.blockId(), operation.contentSha256(),
                operation.assetRequirementId(), operation.approvedAssetId(), operation.assetType(),
                operation.bounds(), operation.requestedTransform(), operation.allowedTransform()));
        CompositionModels.ComposedPresentationPlan changed = withOperations(valid, operations);

        assertThat(changed.planChecksum()).isNotEqualTo(valid.planChecksum());
        assertInvalidReference(request, changed);
    }

    @Test
    void textFitBoundaryCannotEnableRewriteTruncationOrPowerPointAutoFit() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ComposedPresentationPlan valid = validPlan(request);
        CompositionModels.TextFitBoundary original = valid.textFitBoundary();
        CompositionModels.TextFitBoundary unsafe = new CompositionModels.TextFitBoundary(
                original.policyVersion(), original.mode(), original.fontEnvironmentVersion(),
                original.measurementStatus(), original.preserveCharacters(),
                original.preserveParagraphOrder(), original.preserveListHierarchy(),
                true, true, true, true);
        CompositionModels.ComposedPresentationPlan withoutChecksum =
                new CompositionModels.ComposedPresentationPlan(
                        valid.planContractVersion(), valid.requestReference(), valid.generationJobReference(),
                        valid.specificationReference(),
                        valid.templateProfileReference(), valid.approvedAssetManifestReference(), unsafe,
                        valid.originalSlideCount(), valid.slides(), null);
        CompositionModels.ComposedPresentationPlan changed =
                new CompositionModels.ComposedPresentationPlan(
                        withoutChecksum.planContractVersion(), withoutChecksum.requestReference(),
                        withoutChecksum.generationJobReference(),
                        withoutChecksum.specificationReference(), withoutChecksum.templateProfileReference(),
                        withoutChecksum.approvedAssetManifestReference(), withoutChecksum.textFitBoundary(),
                        withoutChecksum.originalSlideCount(), withoutChecksum.slides(),
                        checksumService.computePlan(withoutChecksum));

        assertInvalidReference(request, changed);
    }

    private void assertInvalidReference(
            CompositionModels.EngineComposePlanRequest request,
            CompositionModels.ComposedPresentationPlan plan) {
        assertThat(validator.validate(CompositionTestSupport.validatedPackage(request), plan))
                .extracting(ContractModels.Diagnostic::code)
                .contains("COMPOSITION_REFERENCE_INVALID");
    }

    private void assertSlideSequenceDiagnostic(
            CompositionModels.EngineComposePlanRequest request,
            CompositionModels.ComposedPresentationPlan plan,
            String reference) {
        assertThat(validator.validate(CompositionTestSupport.validatedPackage(request), plan))
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("COMPOSITION_REFERENCE_INVALID");
                    assertThat(diagnostic.safeDetails().get("reference")).isEqualTo(reference);
                });
    }

    private int indexOfType(List<CompositionModels.CompositionOperation> operations, String type) {
        for (int index = 0; index < operations.size(); index++) {
            if (operations.get(index).operationType().name().equals(type)) {
                return index;
            }
        }
        throw new IllegalStateException("operation type missing");
    }

    private CompositionModels.ComposedPresentationPlan withOperations(
            CompositionModels.ComposedPresentationPlan original,
            List<CompositionModels.CompositionOperation> operations) {
        CompositionModels.ComposedSlidePlan originalSlide = original.slides().get(0);
        CompositionModels.ComposedSlidePlan changedSlide = new CompositionModels.ComposedSlidePlan(
                originalSlide.slideId(), originalSlide.pageNumber(), originalSlide.layout(), operations);
        return withSlide(original, changedSlide);
    }

    private CompositionModels.ComposedPresentationPlan withSlide(
            CompositionModels.ComposedPresentationPlan original,
            CompositionModels.ComposedSlidePlan changedSlide) {
        CompositionModels.ComposedPresentationPlan withoutChecksum =
                new CompositionModels.ComposedPresentationPlan(
                        original.planContractVersion(), original.requestReference(),
                        original.generationJobReference(),
                        original.specificationReference(), original.templateProfileReference(),
                        original.approvedAssetManifestReference(),
                        original.textFitBoundary(),
                        original.originalSlideCount(), List.of(changedSlide), null);
        return new CompositionModels.ComposedPresentationPlan(
                withoutChecksum.planContractVersion(), withoutChecksum.requestReference(),
                withoutChecksum.generationJobReference(),
                withoutChecksum.specificationReference(), withoutChecksum.templateProfileReference(),
                withoutChecksum.approvedAssetManifestReference(),
                withoutChecksum.textFitBoundary(),
                withoutChecksum.originalSlideCount(), withoutChecksum.slides(),
                checksumService.computePlan(withoutChecksum));
    }

    private CompositionModels.ComposedPresentationPlan withSlides(
            CompositionModels.ComposedPresentationPlan original,
            List<CompositionModels.ComposedSlidePlan> slides) {
        CompositionModels.ComposedPresentationPlan withoutChecksum =
                new CompositionModels.ComposedPresentationPlan(
                        original.planContractVersion(), original.requestReference(),
                        original.generationJobReference(), original.specificationReference(),
                        original.templateProfileReference(), original.approvedAssetManifestReference(),
                        original.textFitBoundary(), original.originalSlideCount(), slides, null);
        return new CompositionModels.ComposedPresentationPlan(
                withoutChecksum.planContractVersion(), withoutChecksum.requestReference(),
                withoutChecksum.generationJobReference(), withoutChecksum.specificationReference(),
                withoutChecksum.templateProfileReference(), withoutChecksum.approvedAssetManifestReference(),
                withoutChecksum.textFitBoundary(), withoutChecksum.originalSlideCount(),
                withoutChecksum.slides(), checksumService.computePlan(withoutChecksum));
    }

    private CompositionModels.ComposedPresentationPlan validPlan(
            CompositionModels.EngineComposePlanRequest request) {
        ComponentResolver componentResolver = new ComponentResolver();
        TemplatePageResolver pageResolver = new TemplatePageResolver();
        LayoutResolver layoutResolver = new LayoutResolver(idFactory);
        List<CompositionModels.ResolvedLayoutPlan> layouts = new ArrayList<>();
        for (ContractModels.LockedPptSlide slide : request.specification().slides()) {
            var resolved = componentResolver.resolveForComposition(
                    slide, request.templateProfile(),
                    CompositionTestSupport.validatedPackage(request).manifestEntriesByRequirementId());
            var page = pageResolver.resolve(slide, request.templateProfile());
            var layout = layoutResolver.resolveForComposition(
                    slide, request.templateProfile(), resolved, page.selection());
            layouts.add(layout.plan());
        }
        return new SlideComposer(checksumService, idFactory)
                .compose(CompositionTestSupport.validatedPackage(request), layouts).plan();
    }
}
