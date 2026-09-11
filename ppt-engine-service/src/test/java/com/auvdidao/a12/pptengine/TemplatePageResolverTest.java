package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.layout.TemplatePageResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.TEXT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.FIXED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.TRANSLATE_ONLY;

class TemplatePageResolverTest {

    private final TemplatePageResolver resolver = new TemplatePageResolver();

    @Test
    void exactRoleCandidatesUseSourceSlideThenReferenceIdStableOrder() {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        ContractModels.ConfirmedTemplateProfile profile = new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.templateVersion(),
                base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                List.of(
                        new ContractModels.TemplatePageReference("page-z", 1, "BODY", List.of("shape-body", "shape-image")),
                        new ContractModels.TemplatePageReference("page-a", 1, "BODY", List.of("shape-body", "shape-image")),
                        new ContractModels.TemplatePageReference("page-0", 2, "BODY", List.of("shape-body", "shape-image")),
                        new ContractModels.TemplatePageReference("page-title", 1, "TITLE", List.of("shape-body"))),
                base.components());

        TemplatePageResolver.SelectionResult result = resolver.resolve(ContractFixtures.slide(), profile);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.selection().pageReferenceId()).isEqualTo("page-a");
        assertThat(result.selection().sourceSlide()).isEqualTo(1);
        assertThat(result.selection().semanticRole()).isEqualTo("BODY");
        assertThat(result.selection().selectionBasis().name())
                .isEqualTo("EXACT_SEMANTIC_ROLE_STABLE_ORDER");
    }

    @Test
    void missingExactRoleRejectsWithoutFallback() {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        ContractModels.ConfirmedTemplateProfile profile = new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.templateVersion(),
                base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                List.of(new ContractModels.TemplatePageReference(
                        "page-title", 1, "TITLE", List.of("shape-body"))),
                base.components());

        TemplatePageResolver.SelectionResult result = resolver.resolve(ContractFixtures.slide(), profile);

        assertThat(result.selection()).isNull();
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("TEMPLATE_PAGE_MISSING");
            assertThat(diagnostic.source().name()).isEqualTo("TEMPLATE_PAGE_RESOLVER");
            assertThat(diagnostic.safeDetails()).containsEntry("semanticRole", "BODY");
        });
    }

    @Test
    void executablePageWinsOverEarlierPreserveOnlyPageAndTieBreakIsStable() {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        List<ContractModels.TemplatePageReference> pages = List.of(
                new ContractModels.TemplatePageReference("page-preserve", 1, "BODY", List.of("decor")),
                new ContractModels.TemplatePageReference("page-executable-b", 2, "BODY",
                        List.of("shape-body", "shape-image")),
                new ContractModels.TemplatePageReference("page-executable-c", 3, "BODY",
                        List.of("shape-body", "shape-image")));
        List<ContractModels.TemplateComponent> components = new ArrayList<>();
        for (ContractModels.TemplateComponent component : base.components()) {
            components.add(new ContractModels.TemplateComponent(
                    component.componentId(), component.name(), component.semanticRole(), 2,
                    component.shapeRefs(), component.childComponentIds(), component.slots(),
                    component.transformConstraint(), component.fixedStyle(), component.reusable(),
                    component.confidence(), component.teacherConfirmed(), "EXECUTION_READY"));
        }
        ContractModels.ConfirmedTemplateProfile profile = new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.projectId(), base.ownerUserId(),
                base.templateVersion(), base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                pages, components, base.preservedNativeObjects(), base.textFitPolicy(), base.executionStatus(),
                base.sourceVersionId(), base.sourceSha256(), base.parserSnapshotChecksum());

        TemplatePageResolver.SelectionResult result = resolver.resolve(ContractFixtures.slide(), profile);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.selection()).isNotNull();
        assertThat(result.selection().pageReferenceId()).isEqualTo("page-executable-b");
        assertThat(result.selection().sourceSlide()).isEqualTo(2);
    }

    @Test
    void roleMatchWithoutLegalExecutableSlotFailsClosed() {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        ContractModels.TemplateComponent notReady = new ContractModels.TemplateComponent(
                "not-ready", "装饰", "BODY", 1, base.components().get(0).shapeRefs(), List.of(),
                base.components().get(0).slots(), base.components().get(0).transformConstraint(),
                base.components().get(0).fixedStyle(), false, 1.0, false, "NOT_EXECUTION_READY");
        ContractModels.ConfirmedTemplateProfile profile = new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.projectId(), base.ownerUserId(),
                base.templateVersion(), base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                List.of(new ContractModels.TemplatePageReference("page-preserve", 1, "BODY", List.of("decor"))),
                List.of(notReady), base.preservedNativeObjects(), base.textFitPolicy(), base.executionStatus(),
                base.sourceVersionId(), base.sourceSha256(), base.parserSnapshotChecksum());

        TemplatePageResolver.SelectionResult result = resolver.resolve(ContractFixtures.slide(), profile);

        assertThat(result.selection()).isNull();
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("TEMPLATE_PAGE_MISSING");
            assertThat(diagnostic.safeDetails()).containsEntry("reason", "noLegalExecutableComponentOrSlot");
        });
    }

    @Test
    void transformIncompatiblePageIsFilteredBeforePageRanking() {
        ContractModels.LockedPptSlide slide = slideWithoutAssets();
        ContractModels.ConfirmedTemplateProfile profile = profileFor(
                List.of(
                        page("page-incompatible", 1, "shape-incompatible"),
                        page("page-compatible", 2, "shape-compatible")),
                List.of(
                        component("component-incompatible", 1, "shape-incompatible", FIXED, 1000),
                        component("component-compatible", 2, "shape-compatible", TRANSLATE_ONLY, 1000)));

        TemplatePageResolver.SelectionResult result = resolver.resolve(slide, profile);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.selection().pageReferenceId()).isEqualTo("page-compatible");
    }

    @Test
    void insufficientCapacityPageIsFilteredBeforePageRanking() {
        ContractModels.LockedPptSlide slide = slideWithoutAssets();
        ContractModels.ConfirmedTemplateProfile profile = profileFor(
                List.of(
                        page("page-overflow", 1, "shape-overflow"),
                        page("page-capable", 2, "shape-capable")),
                List.of(
                        component("component-overflow", 1, "shape-overflow", TRANSLATE_ONLY, 2),
                        component("component-capable", 2, "shape-capable", TRANSLATE_ONLY, 1000)));

        TemplatePageResolver.SelectionResult result = resolver.resolve(slide, profile);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.selection().pageReferenceId()).isEqualTo("page-capable");
    }

    @Test
    void pageScopeMismatchIsNotExecutableAndFailsClosed() {
        ContractModels.ConfirmedTemplateProfile profile = profileFor(
                List.of(page("page-wrong-scope", 1, "page-object")),
                List.of(component("component-outside-page", 1, "different-object", TRANSLATE_ONLY, 1000)));

        TemplatePageResolver.SelectionResult result = resolver.resolve(slideWithoutAssets(), profile);

        assertThat(result.selection()).isNull();
        assertThat(result.diagnostics()).singleElement()
                .extracting(ContractModels.Diagnostic::safeDetails)
                .satisfies(details -> assertThat(details)
                        .containsEntry("reason", "noLegalExecutableComponentOrSlot"));
    }

    @Test
    void duplicateStableReferencePageIsRejectedBeforePageRanking() {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        ContractModels.LockedPptContentBlock secondBlock = new ContractModels.LockedPptContentBlock(
                "block-002", BODY, "第二个内容块", base.contentBlocks().get(0).sourceType(),
                "material-002", true);
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                List.of(base.contentBlocks().get(0), secondBlock), base.semanticLayout(),
                List.of(), base.provenance(), base.notes());

        ContractModels.TemplateComponent conflicting = componentWithRefsAndSlots(
                "component-conflicting", 1, List.of("shared-native"), 2, false);
        ContractModels.TemplateComponent legalOne = componentWithRefsAndSlots(
                "component-legal-1", 2, List.of("legal-native-1"), 1, false);
        ContractModels.TemplateComponent legalTwo = componentWithRefsAndSlots(
                "component-legal-2", 2, List.of("legal-native-2"), 1, false);
        ContractModels.ConfirmedTemplateProfile profile = profileFor(
                List.of(
                        new ContractModels.TemplatePageReference(
                                "page-conflicting", 1, "BODY", List.of("shared-native")),
                        new ContractModels.TemplatePageReference(
                                "page-legal", 2, "BODY", List.of("legal-native-1", "legal-native-2"))),
                List.of(conflicting, legalOne, legalTwo));

        TemplatePageResolver.SelectionResult result = resolver.resolve(slide, profile);

        assertThat(result.selection()).isNotNull();
        assertThat(result.selection().pageReferenceId()).isEqualTo("page-legal");
        assertThat(result.rankedFeasiblePageReferenceIds()).containsExactly("page-legal");
        assertThat(result.decisions()).filteredOn(
                decision -> decision.pageReferenceId().equals("page-conflicting"))
                .singleElement().satisfies(decision -> {
                    assertThat(decision.feasible()).isFalse();
                    assertThat(decision.rejectionReasons())
                            .contains("STABLE_NATIVE_REFERENCE_REUSE_NOT_ALLOWED");
                });
    }

    @Test
    void candidateOrderingIsIndependentOfInputPermutationForTwentyRuns() {
        ContractModels.LockedPptSlide slide = slideWithoutAssets();
        List<ContractModels.TemplatePageReference> pages = List.of(
                page("page-03", 3, "shape-03"),
                page("page-01", 1, "shape-01"),
                page("page-04", 4, "shape-04"),
                page("page-02", 2, "shape-02"));
        List<ContractModels.TemplateComponent> components = List.of(
                component("component-03", 3, "shape-03", TRANSLATE_ONLY, 1000),
                component("component-01", 1, "shape-01", TRANSLATE_ONLY, 1000),
                component("component-04", 4, "shape-04", TRANSLATE_ONLY, 1000),
                component("component-02", 2, "shape-02", TRANSLATE_ONLY, 1000));

        for (int seed = 0; seed < 20; seed++) {
            List<ContractModels.TemplatePageReference> shuffledPages = new ArrayList<>(pages);
            List<ContractModels.TemplateComponent> shuffledComponents = new ArrayList<>(components);
            Collections.shuffle(shuffledPages, new Random(seed));
            Collections.shuffle(shuffledComponents, new Random(seed * 31L + 7));

            TemplatePageResolver.SelectionResult result = resolver.resolve(
                    slide, profileFor(shuffledPages, shuffledComponents));

            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.selection().pageReferenceId()).isEqualTo("page-01");
            assertThat(result.selection().sourceSlide()).isEqualTo(1);
        }
    }

    private ContractModels.LockedPptSlide slideWithoutAssets() {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        return new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                base.contentBlocks(), base.semanticLayout(), List.of(), base.provenance(), base.notes());
    }

    private ContractModels.TemplatePageReference page(String id, int sourceSlide, String objectId) {
        return new ContractModels.TemplatePageReference(id, sourceSlide, "BODY", List.of(objectId));
    }

    private ContractModels.TemplateComponent component(
            String id, int sourceSlide, String objectId,
            ContractTypes.TransformConstraint transform, int maxCharacters) {
        return new ContractModels.TemplateComponent(
                id, id, "BODY", sourceSlide,
                List.of(new ContractModels.StableObjectReference(TEXT, objectId)), List.of(),
                List.of(new ContractModels.ComponentSlot(
                        "slot-" + id, "BODY", List.of(BODY),
                        new ContractModels.Bounds(500000, 500000, 5000000, 2500000), true,
                        new ContractModels.CapacityConstraint(maxCharacters, 1))),
                transform, new ContractModels.FixedStyle("style-" + id, "font", "color", true),
                true, 0.9, true, "EXECUTION_READY");
    }

    private ContractModels.TemplateComponent componentWithRefsAndSlots(
            String id, int sourceSlide, List<String> objectIds, int slotCount, boolean reusable) {
        List<ContractModels.ComponentSlot> slots = new ArrayList<>();
        for (int index = 0; index < slotCount; index++) {
            slots.add(new ContractModels.ComponentSlot(
                    id + "-slot-" + index, "BODY", List.of(BODY),
                    new ContractModels.Bounds(500000 + index * 100000, 500000, 5000000, 2500000),
                    index == 0, new ContractModels.CapacityConstraint(1000, 1)));
        }
        return new ContractModels.TemplateComponent(
                id, id, "BODY", sourceSlide,
                objectIds.stream().map(objectId -> new ContractModels.StableObjectReference(TEXT, objectId)).toList(),
                List.of(), slots, TRANSLATE_ONLY,
                new ContractModels.FixedStyle(id + "-style", "font", "color", true),
                reusable, 1.0, true, "EXECUTION_READY");
    }

    private ContractModels.ConfirmedTemplateProfile profileFor(
            List<ContractModels.TemplatePageReference> pages,
            List<ContractModels.TemplateComponent> components) {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        return new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.projectId(), base.ownerUserId(),
                base.templateVersion(), base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                pages, components, base.preservedNativeObjects(), base.textFitPolicy(), base.executionStatus(),
                base.sourceVersionId(), base.sourceSha256(), base.parserSnapshotChecksum());
    }
}
