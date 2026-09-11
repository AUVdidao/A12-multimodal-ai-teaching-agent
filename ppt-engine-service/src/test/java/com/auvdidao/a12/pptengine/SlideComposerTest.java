package com.auvdidao.a12.pptengine;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlideComposerTest {

    private static final String PLAN_CHECKSUM_VECTOR =
            "cedb8f96e7a3c3e30158475fad3aab9af60b7b6eaca6ee170051352365acd2df";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ChecksumService checksumService = new ChecksumService(objectMapper);
    private final StablePlanIdFactory idFactory = new StablePlanIdFactory(checksumService);
    private final ComponentResolver componentResolver = new ComponentResolver();
    private final TemplatePageResolver pageResolver = new TemplatePageResolver();
    private final LayoutResolver layoutResolver = new LayoutResolver(idFactory);
    private final SlideComposer composer = new SlideComposer(checksumService, idFactory);

    @Test
    void operationsPreserveFrozenCategoryAndReferenceOrder() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);

        CompositionModels.ComposedPresentationPlan plan = compose(request);

        assertThat(plan.slides()).singleElement().satisfies(slide -> {
            assertThat(slide.operations()).extracting(operation -> operation.operationType().name())
                    .containsExactly(
                            "PRESERVE_BASE_OBJECT", "PRESERVE_BASE_OBJECT",
                            "USE_OR_CLONE_COMPONENT_OBJECT", "USE_OR_CLONE_COMPONENT_OBJECT",
                            "FILL_TEXT_SLOT", "FILL_ASSET_SLOT");
            assertThat(slide.operations().subList(0, 2))
                    .extracting(operation -> operation.nativeObjectReference().objectId())
                    .containsExactly("shape-body", "shape-image");
            assertThat(slide.operations().subList(0, 2))
                    .extracting(operation -> operation.nativeObjectReference().objectType().name())
                    .containsOnly("UNKNOWN");
            assertThat(slide.operations().subList(2, 4))
                    .extracting(CompositionModels.CompositionOperation::componentId)
                    .containsExactly("component-body", "component-image");
            assertThat(slide.operations().subList(2, 4))
                    .extracting(operation -> operation.componentObjectAction().name())
                    .containsOnly("USE_EXISTING_OBJECT");
            assertThat(slide.operations().get(4).contentSha256())
                    .isEqualTo(checksumService.sha256Utf8(
                            request.specification().slides().get(0).contentBlocks().get(0).content()));
            assertThat(slide.operations().get(5).assetRequirementId()).isEqualTo("asset-001");
            assertThat(slide.operations().get(5).approvedAssetId()).isEqualTo("approved-asset-001");
            assertThat(slide.operations().get(5).assetType().name()).isEqualTo("IMAGE");
        });
    }

    @Test
    void crossSourceComponentOutsideSelectedPageFailsClosed() {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        List<ContractModels.TemplateComponent> components = new ArrayList<>();
        for (ContractModels.TemplateComponent component : base.components()) {
            components.add(component.componentId().equals("component-image")
                    ? CompositionTestSupport.copyComponent(
                    component, 2, component.shapeRefs(), component.slots())
                    : component);
        }
        ContractModels.ConfirmedTemplateProfile profile =
                CompositionTestSupport.withComponents(base, components);
        profile = CompositionTestSupport.withPages(profile, List.of(
                new ContractModels.TemplatePageReference(
                        "page-ref-001", 1, "BODY", List.of("shape-body", "shape-image")),
                new ContractModels.TemplatePageReference(
                        "page-ref-source-2", 2, "COMPONENT_LIBRARY", List.of("shape-image"))));
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(
                objectMapper, "req-clone", List.of(ContractFixtures.slide()), profile);

        var page = pageResolver.resolve(ContractFixtures.slide(), profile);

        assertThat(page.selection()).isNull();
        assertThat(page.diagnostics()).singleElement()
                .extracting(ContractModels.Diagnostic::safeDetails)
                .satisfies(details -> assertThat(details)
                        .containsEntry("reason", "noFeasibleExecutablePage"));
    }

    @Test
    void planBytesAndChecksumAreStableForTwentyRunsAndInputsStayUnchanged() throws Exception {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        String inputBefore = objectMapper.writeValueAsString(request);
        List<String> bytes = new ArrayList<>();
        List<String> checksums = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            CompositionModels.ComposedPresentationPlan plan = compose(request);
            bytes.add(objectMapper.writeValueAsString(plan));
            checksums.add(plan.planChecksum());
            assertThat(checksumService.computePlan(plan)).isEqualTo(plan.planChecksum());
        }

        assertThat(bytes).allMatch(bytes.get(0)::equals);
        assertThat(checksums).containsOnly(checksums.get(0));
        assertThat(objectMapper.writeValueAsString(request)).isEqualTo(inputBefore);
        assertThat(bytes.get(0)).doesNotContain("generatedAt");
    }

    @Test
    void oneCharacterChangeChangesContentHashAndPlanChecksum() {
        CompositionModels.EngineComposePlanRequest original = CompositionTestSupport.request(objectMapper);
        ContractModels.LockedPptSlide baseSlide = original.specification().slides().get(0);
        ContractModels.LockedPptContentBlock baseBlock = baseSlide.contentBlocks().get(0);
        ContractModels.LockedPptContentBlock changedBlock = new ContractModels.LockedPptContentBlock(
                baseBlock.blockId(), baseBlock.type(), baseBlock.content() + "。", baseBlock.sourceType(),
                baseBlock.sourceReference(), baseBlock.locked());
        ContractModels.LockedPptSlide changedSlide = new ContractModels.LockedPptSlide(
                baseSlide.slideId(), baseSlide.pageNumber(), baseSlide.title(), baseSlide.teachingGoal(),
                List.of(changedBlock), baseSlide.semanticLayout(), baseSlide.assetRequirements(),
                baseSlide.provenance(), baseSlide.notes());
        CompositionModels.EngineComposePlanRequest changed = CompositionTestSupport.request(
                objectMapper, original.requestId(), List.of(changedSlide), original.templateProfile());

        CompositionModels.ComposedPresentationPlan originalPlan = compose(original);
        CompositionModels.ComposedPresentationPlan changedPlan = compose(changed);

        assertThat(textHash(originalPlan)).isNotEqualTo(textHash(changedPlan));
        assertThat(originalPlan.planChecksum()).isNotEqualTo(changedPlan.planChecksum());
    }

    @Test
    void approvedManifestChangeCreatesNewJobAndChangesPlanChecksum() {
        CompositionModels.EngineComposePlanRequest original = CompositionTestSupport.request(objectMapper);
        CompositionModels.ApprovedAssetManifestEntry base =
                original.approvedAssetManifest().entries().get(0);
        CompositionModels.ApprovedAssetManifestEntry replacement =
                new CompositionModels.ApprovedAssetManifestEntry(
                        base.assetRequirementId(), base.resolution(), "approved-asset-replacement",
                        base.assetType(), checksumService.sha256Utf8("replacement-bytes"));
        CompositionModels.EngineComposePlanRequest changed =
                CompositionTestSupport.withManifestEntries(
                        objectMapper, original, "job-manifest-v2", 2, List.of(replacement));

        CompositionModels.ComposedPresentationPlan originalPlan = compose(original);
        CompositionModels.ComposedPresentationPlan changedPlan = compose(changed);
        CompositionModels.CompositionOperation changedFill = changedPlan.slides().get(0).operations().stream()
                .filter(operation -> operation.operationType().name().equals("FILL_ASSET_SLOT"))
                .findFirst().orElseThrow();

        assertThat(changed.generationJob().generationJobId())
                .isNotEqualTo(original.generationJob().generationJobId());
        assertThat(changedFill.approvedAssetId()).isEqualTo("approved-asset-replacement");
        assertThat(changedPlan.approvedAssetManifestReference().manifestVersion()).isEqualTo(2);
        assertThat(changedPlan.planChecksum()).isNotEqualTo(originalPlan.planChecksum());
    }

    @Test
    void generationJobBindingAndAttemptArePartOfThePlanChecksum() {
        CompositionModels.EngineComposePlanRequest original = CompositionTestSupport.request(objectMapper);
        CompositionModels.EngineComposePlanRequest changed = CompositionTestSupport.withGenerationJob(
                objectMapper, original, "job-002", "attempt-002",
                "engine-build-test-2", "executor-adapter-none-2", "font-env-test-2");

        CompositionModels.ComposedPresentationPlan originalPlan = compose(original);
        CompositionModels.ComposedPresentationPlan changedPlan = compose(changed);

        assertThat(changedPlan.generationJobReference().generationJobId()).isEqualTo("job-002");
        assertThat(changedPlan.generationJobReference().executionAttemptId()).isEqualTo("attempt-002");
        assertThat(changedPlan.generationJobReference().jobBindingChecksum())
                .isEqualTo(changed.generationJob().jobBindingChecksum());
        assertThat(changedPlan.planChecksum()).isNotEqualTo(originalPlan.planChecksum());
    }

    @Test
    void canonicalPlanChecksumMatchesFrozenExampleVector() throws Exception {
        var raw = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v1/examples/valid/preflight-request.json"));
        ContractModels.EnginePreflightRequest preflight = objectMapper.treeToValue(
                raw, ContractModels.EnginePreflightRequest.class);
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(
                objectMapper, preflight.requestId(), preflight.specification(), preflight.templateProfile());

        CompositionModels.ComposedPresentationPlan plan = compose(request);

        assertThat(plan.planChecksum()).isEqualTo(PLAN_CHECKSUM_VECTOR);
        assertThat(checksumService.computePlan(plan)).isEqualTo(PLAN_CHECKSUM_VECTOR);
    }

    @Test
    void twoSlidePlanPreservesSpecificationPageOrderAndAllInputObjects() throws Exception {
        ContractModels.LockedPptSlide first = ContractFixtures.slide();
        ContractModels.LockedPptContentBlock firstBlock = first.contentBlocks().get(0);
        ContractModels.LockedPptAssetReference firstAsset = first.assetRequirements().get(0);
        ContractModels.LockedPptSlide second = new ContractModels.LockedPptSlide(
                "slide-002", 2, "第二页标题", "第二页目标",
                List.of(new ContractModels.LockedPptContentBlock(
                        "block-002", firstBlock.type(), "第二页逐字原文", firstBlock.sourceType(),
                        "material-002", true)),
                new ContractModels.SemanticLayout(
                        first.semanticLayout().primaryRole(),
                        List.of(new ContractModels.SemanticRegion(
                                "region-002", "BODY",
                                first.semanticLayout().regions().get(0).preferredPosition(), 1)),
                        first.semanticLayout().requestedTransform()),
                List.of(new ContractModels.LockedPptAssetReference(
                        "asset-002", firstAsset.assetType(), "asset-source-002",
                        firstAsset.approvalStatus(), true, firstAsset.placementIntent())),
                List.of(new ContractModels.ProvenanceEntry(firstBlock.sourceType(), "material-002")),
                "第二页备注");
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(
                objectMapper, "req-two-slides", List.of(first, second), ContractFixtures.profile());
        String before = objectMapper.writeValueAsString(request);

        CompositionModels.ComposedPresentationPlan plan = compose(request);

        assertThat(plan.originalSlideCount()).isEqualTo(2);
        assertThat(plan.slides()).extracting(CompositionModels.ComposedSlidePlan::slideId)
                .containsExactly("slide-001", "slide-002");
        assertThat(plan.slides()).extracting(CompositionModels.ComposedSlidePlan::pageNumber)
                .containsExactly(1, 2);
        assertThat(plan.slides().get(0).operations()).extracting(
                CompositionModels.CompositionOperation::blockId).contains("block-001");
        assertThat(plan.slides().get(1).operations()).extracting(
                CompositionModels.CompositionOperation::blockId).contains("block-002");
        assertThat(objectMapper.writeValueAsString(request)).isEqualTo(before);
        assertThat(objectMapper.writeValueAsString(plan))
                .doesNotContain("植物利用光能")
                .doesNotContain("第二页逐字原文")
                .doesNotContain("material-002")
                .doesNotContain("asset-source-002");
    }

    private String textHash(CompositionModels.ComposedPresentationPlan plan) {
        return plan.slides().get(0).operations().stream()
                .filter(operation -> operation.operationType().name().equals("FILL_TEXT_SLOT"))
                .findFirst().orElseThrow().contentSha256();
    }

    private CompositionModels.ComposedPresentationPlan compose(
            CompositionModels.EngineComposePlanRequest request) {
        List<CompositionModels.ResolvedLayoutPlan> layouts = new ArrayList<>();
        CompositionModels.ValidatedExecutionPackage executionPackage =
                CompositionTestSupport.validatedPackage(request);
        for (ContractModels.LockedPptSlide slide : request.specification().slides()) {
            var page = pageResolver.resolve(slide, request.templateProfile());
            assertThat(page.diagnostics()).isEmpty();
            ComponentResolver.ResolverResult resolver = componentResolver.resolveForComposition(
                    slide, request.templateProfile(), executionPackage.manifestEntriesByRequirementId(),
                    page.selection());
            assertThat(resolver.diagnostics()).noneMatch(item ->
                    item.severity() == com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.ERROR);
            LayoutResolver.LayoutResult layout = layoutResolver.resolveForComposition(
                    slide, request.templateProfile(), resolver, page.selection());
            assertThat(layout.diagnostics()).isEmpty();
            layouts.add(layout.plan());
        }
        SlideComposer.ComposerResult result = composer.compose(executionPackage, layouts);
        assertThat(result.diagnostics()).isEmpty();
        return result.plan();
    }
}
