package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.composition.StablePlanIdFactory;
import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.layout.LayoutResolver;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentSourceType.MATERIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.GROUP;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.CENTER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.FULL_BLEED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.TRANSLATE_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

class LayoutResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final StablePlanIdFactory idFactory =
            new StablePlanIdFactory(new ChecksumService(objectMapper));
    private final LayoutResolver layoutResolver = new LayoutResolver(idFactory);
    private final ComponentResolver componentResolver = new ComponentResolver();

    @Test
    void validLayoutCopiesConfirmedIntegerEmuBoundsAndUsesNoRegionFallbackForAsset() {
        ContractModels.LockedPptSlide slide = ContractFixtures.slide();
        ContractModels.ConfirmedTemplateProfile profile = ContractFixtures.profile();

        LayoutResolver.LayoutResult result = resolve(slide, profile);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.plan().pageSize()).isEqualTo(profile.pageSize());
        assertThat(result.plan().safeArea())
                .isEqualTo(new ContractModels.Bounds(300000, 300000, 11592000, 6258000));
        assertThat(result.plan().slotPlacements()).hasSize(2);
        assertThat(result.plan().slotPlacements().get(0)).satisfies(placement -> {
            assertThat(placement.bindingKind()).isEqualTo(ContractTypes.SlotBindingKind.TEXT);
            assertThat(placement.bindingId()).isEqualTo("block-001");
            assertThat(placement.regionId()).isEqualTo("region-001");
            assertThat(placement.bounds())
                    .isEqualTo(new ContractModels.Bounds(500000, 500000, 5000000, 2500000));
            assertThat(placement.requestedTransform()).isEqualTo(TRANSLATE_ONLY);
            assertThat(placement.allowedTransform()).isEqualTo(TRANSLATE_ONLY);
        });
        assertThat(result.plan().slotPlacements().get(1).bindingId()).isEqualTo("asset-001");
        assertThat(result.plan().slotPlacements().get(1).regionId()).isNull();
    }

    @Test
    void pageOverflowIsRejectedWithPreciseSlotDiagnostic() {
        ContractModels.Bounds bounds = new ContractModels.Bounds(12191900, 500000, 200, 1000);
        LayoutResolver.LayoutResult result = resolve(singleBlockSlide(CENTER), singleComponentProfile(bounds, 1));

        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly("LAYOUT_OUT_OF_BOUNDS");
        assertThat(result.diagnostics().get(0).componentId()).isEqualTo("component-body");
        assertThat(result.diagnostics().get(0).slotId()).isEqualTo("slot-body");
    }

    @Test
    void ordinaryRegionMustStayInsideSafeArea() {
        ContractModels.Bounds bounds = new ContractModels.Bounds(0, 0, 1000, 1000);
        LayoutResolver.LayoutResult result = resolve(singleBlockSlide(CENTER), singleComponentProfile(bounds, 1));

        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly("SAFE_AREA_VIOLATION");
    }

    @Test
    void fullBleedMayTouchPageEdgeButStillCannotCrossIt() {
        ContractModels.Bounds edge = new ContractModels.Bounds(0, 0, 12192000, 6858000);
        LayoutResolver.LayoutResult valid = resolve(singleBlockSlide(FULL_BLEED), singleComponentProfile(edge, 1));
        assertThat(valid.diagnostics()).isEmpty();
        assertThat(valid.plan().slotPlacements().get(0).bounds()).isEqualTo(edge);

        ContractModels.Bounds overflow = new ContractModels.Bounds(0, 0, 12192001, 6858000);
        LayoutResolver.LayoutResult invalid = resolve(
                singleBlockSlide(FULL_BLEED), singleComponentProfile(overflow, 1));
        assertThat(invalid.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly("LAYOUT_OUT_OF_BOUNDS");
    }

    @Test
    void matchingRegionsConsumeSpecificationOrderAndEnforceEachMaxItems() {
        List<ContractModels.LockedPptContentBlock> blocks = List.of(
                block("block-1"), block("block-2"), block("block-3"));
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                "slide-regions", 1, "标题", "目标", blocks,
                new ContractModels.SemanticLayout("BODY", List.of(
                        new ContractModels.SemanticRegion("region-first", "BODY", CENTER, 1),
                        new ContractModels.SemanticRegion("region-second", "BODY", CENTER, 1)),
                        TRANSLATE_ONLY),
                List.of(), List.of(), "");

        LayoutResolver.LayoutResult result = resolve(
                slide,
                singleComponentProfile(new ContractModels.Bounds(500000, 500000, 1000000, 1000000), 3));

        assertThat(result.plan().slotPlacements()).extracting(CompositionModels.SlotPlacement::regionId)
                .containsExactly("region-first", "region-second");
        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly("SEMANTIC_REGION_CAPACITY_EXCEEDED");
        assertThat(result.diagnostics().get(0).blockId()).isEqualTo("block-3");
    }

    private LayoutResolver.LayoutResult resolve(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile) {
        ComponentResolver.ResolverResult resolverResult = componentResolver.resolve(slide, profile);
        assertThat(resolverResult.diagnostics()).isEmpty();
        var page = profile.templatePageReferences().get(0);
        var selection = new CompositionModels.TemplatePageSelection(
                page.pageReferenceId(), page.sourceSlide(), page.semanticRole(),
                ContractTypes.TemplatePageSelectionBasis.EXACT_SEMANTIC_ROLE_STABLE_ORDER);
        return layoutResolver.resolve(slide, profile, resolverResult.plan(), selection);
    }

    private ContractModels.LockedPptSlide singleBlockSlide(ContractTypes.PreferredPosition position) {
        return new ContractModels.LockedPptSlide(
                "slide-single", 1, "标题", "目标", List.of(block("block-single")),
                new ContractModels.SemanticLayout(
                        "BODY", List.of(new ContractModels.SemanticRegion(
                        "region-single", "BODY", position, 1)), TRANSLATE_ONLY),
                List.of(), List.of(), "");
    }

    private ContractModels.LockedPptContentBlock block(String id) {
        return new ContractModels.LockedPptContentBlock(
                id, BODY, "保持原文", MATERIAL, "material-001", true);
    }

    private ContractModels.ConfirmedTemplateProfile singleComponentProfile(
            ContractModels.Bounds bounds,
            int maxItems) {
        ContractModels.TemplateComponent component = new ContractModels.TemplateComponent(
                "component-body", "正文", "BODY", 1,
                List.of(new ContractModels.StableObjectReference(GROUP, "shape-body")),
                List.of(),
                List.of(new ContractModels.ComponentSlot(
                        "slot-body", "BODY", List.of(BODY), bounds, true,
                        new ContractModels.CapacityConstraint(1000, maxItems))),
                TRANSLATE_ONLY,
                new ContractModels.FixedStyle("style-body", "font-body", "color-body", true),
                true, 1.0, true);
        return new ContractModels.ConfirmedTemplateProfile(
                ContractTypes.V1, "profile-001", "template-001", 1, 1,
                ContractTypes.TemplateProfileStatus.CONFIRMED,
                new ContractModels.PageSize(12192000, 6858000),
                new ContractModels.SpatialProfile(300000, 300000, 300000, 300000),
                List.of(new ContractModels.TemplatePageReference(
                        "page-body", 1, "BODY", List.of("shape-body"))),
                List.of(component));
    }
}
