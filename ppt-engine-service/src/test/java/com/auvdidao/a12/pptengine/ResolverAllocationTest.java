package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.APPROVED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.CHART;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.IMAGE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.TABLE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentSourceType.MATERIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TEXT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.ERROR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.GROUP;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.CENTER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TemplateProfileStatus.CONFIRMED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.TRANSLATE_ONLY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.V1;
import static org.assertj.core.api.Assertions.assertThat;

class ResolverAllocationTest {

    private final ComponentResolver resolver = new ComponentResolver();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void maxItemsOneLeavesSecondBodyBlockUnresolved() {
        ContractModels.ComponentSlot slot = slot("slot-body", "BODY", List.of(BODY), true, 100, 1);
        var result = resolve(
                slide(List.of(block("block-1", "one"), block("block-2", "two")), List.of()),
                profile(List.of(component("component-body", "BODY", List.of(slot)))));

        assertThat(result.plan().blockToSlotBindings())
                .containsExactlyEntriesOf(java.util.Map.of("block-1", "slot-body"));
        assertThat(result.plan().unresolvedBlockIds()).containsExactly("block-2");
        assertThat(result.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("SLOT_CAPACITY_EXCEEDED");
            assertThat(diagnostic.severity()).isEqualTo(ERROR);
            assertThat(diagnostic.blockId()).isEqualTo("block-2");
            assertThat(diagnostic.componentId()).isEqualTo("component-body");
            assertThat(diagnostic.slotId()).isEqualTo("slot-body");
            assertThat(diagnostic.safeDetails()).containsEntry("constraint", "maxItems");
        });
    }

    @Test
    void maxItemsTwoAllowsTwoBodyBlocks() {
        ContractModels.ComponentSlot slot = slot("slot-body", "BODY", List.of(BODY), true, 100, 2);
        var result = resolve(
                slide(List.of(block("block-1", "one"), block("block-2", "two")), List.of()),
                profile(List.of(component("component-body", "BODY", List.of(slot)))));

        assertThat(result.plan().blockToSlotBindings())
                .containsEntry("block-1", "slot-body")
                .containsEntry("block-2", "slot-body");
        assertThat(result.plan().unresolvedBlockIds()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void twoSlotsAreFilledInFrozenContractOrder() {
        var component = component("component-body", "BODY", List.of(
                slot("slot-first", "BODY", List.of(BODY), true, 100, 1),
                slot("slot-second", "BODY", List.of(BODY), true, 100, 1)));
        var result = resolve(
                slide(List.of(block("block-1", "one"), block("block-2", "two")), List.of()),
                profile(List.of(component)));

        assertThat(result.plan().blockToSlotBindings())
                .containsEntry("block-1", "slot-first")
                .containsEntry("block-2", "slot-second");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void nullMaxItemsMeansNoItemLimit() {
        ContractModels.ComponentSlot slot = slot("slot-body", "BODY", List.of(BODY), true, 100, null);
        var result = resolve(
                slide(List.of(block("block-1", "one"), block("block-2", "two")), List.of()),
                profile(List.of(component("component-body", "BODY", List.of(slot)))));

        assertThat(result.plan().blockToSlotBindings()).hasSize(2);
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void blockAndAssetShareTheSameSlotOccupancy() {
        ContractModels.ComponentSlot slot = slot(
                "slot-shared", "BODY", List.of(BODY, ContractTypes.ContentType.IMAGE), true, 100, 1);
        var result = resolve(
                slide(List.of(block("block-1", "one")), List.of(asset(
                        "asset-1", IMAGE, ContractTypes.PlacementIntent.BODY))),
                profile(List.of(component("component-shared", "BODY", List.of(slot)))));

        assertThat(result.plan().blockToSlotBindings()).containsEntry("block-1", "slot-shared");
        assertThat(result.plan().assetToSlotBindings()).isEmpty();
        assertThat(result.plan().unresolvedAssetIds()).containsExactly("asset-1");
        assertThat(result.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("SLOT_CAPACITY_EXCEEDED");
            assertThat(diagnostic.assetId()).isEqualTo("asset-1");
            assertThat(diagnostic.slotId()).isEqualTo("slot-shared");
        });
    }

    @Test
    void twoAssetsCannotOverbookOneSlot() {
        ContractModels.ComponentSlot slot = slot(
                "slot-image", "IMAGE", List.of(ContractTypes.ContentType.IMAGE), true, null, 1);
        var result = resolve(
                slide(List.of(), List.of(
                        asset("asset-1", IMAGE, ContractTypes.PlacementIntent.IMAGE),
                        asset("asset-2", IMAGE, ContractTypes.PlacementIntent.IMAGE))),
                profile(List.of(component("component-image", "BODY", List.of(slot)))));

        assertThat(result.plan().assetToSlotBindings())
                .containsExactlyEntriesOf(java.util.Map.of("asset-1", "slot-image"));
        assertThat(result.plan().unresolvedAssetIds()).containsExactly("asset-2");
        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly("SLOT_CAPACITY_EXCEEDED");
    }

    @Test
    void selectedComponentWithUnfilledRequiredSlotIsAnError() {
        var component = component("component-complete", "BODY", List.of(
                slot("slot-body", "BODY", List.of(BODY), true, 100, 1),
                slot("slot-required-image", "IMAGE", List.of(ContractTypes.ContentType.IMAGE), true, null, 1)));
        var result = resolve(
                slide(List.of(block("block-1", "one")), List.of()),
                profile(List.of(component)));

        assertThat(result.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("REQUIRED_SLOT_UNFILLED");
            assertThat(diagnostic.severity()).isEqualTo(ERROR);
            assertThat(diagnostic.componentId()).isEqualTo("component-complete");
            assertThat(diagnostic.slotId()).isEqualTo("slot-required-image");
        });
    }

    @ParameterizedTest
    @EnumSource(value = ContractTypes.AssetType.class, names = {"VIDEO", "ICON", "OTHER"})
    void unsupportedAssetTypesAreNeverReinterpretedAsText(ContractTypes.AssetType unsupportedType) {
        var textSlot = slot("slot-text", "BODY", List.of(TEXT), false, 100, 1);
        var result = resolve(
                slide(List.of(), List.of(asset(
                        "asset-unsupported", unsupportedType, ContractTypes.PlacementIntent.BODY))),
                profile(List.of(component("component-text", "BODY", List.of(textSlot)))));

        assertThat(result.plan().assetToSlotBindings()).isEmpty();
        assertThat(result.plan().unresolvedAssetIds()).containsExactly("asset-unsupported");
        assertThat(result.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("ASSET_TYPE_UNSUPPORTED");
            assertThat(diagnostic.assetId()).isEqualTo("asset-unsupported");
            assertThat(diagnostic.safeDetails()).containsEntry("assetType", unsupportedType.name());
        });
    }

    @Test
    void imageChartAndTableKeepTheirOneToOneMappings() {
        var component = component("component-assets", "BODY", List.of(
                slot("slot-image", "IMAGE", List.of(ContractTypes.ContentType.IMAGE), true, null, 1),
                slot("slot-chart", "CHART", List.of(ContractTypes.ContentType.CHART), true, null, 1),
                slot("slot-table", "TABLE", List.of(ContractTypes.ContentType.TABLE), true, null, 1)));
        var result = resolve(
                slide(List.of(), List.of(
                        asset("asset-image", IMAGE, ContractTypes.PlacementIntent.IMAGE),
                        asset("asset-chart", CHART, ContractTypes.PlacementIntent.CHART),
                        asset("asset-table", TABLE, ContractTypes.PlacementIntent.TABLE))),
                profile(List.of(component)));

        assertThat(result.plan().assetToSlotBindings())
                .containsEntry("asset-image", "slot-image")
                .containsEntry("asset-chart", "slot-chart")
                .containsEntry("asset-table", "slot-table");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void maxCharactersIsCheckedPerBlockWithoutChangingContent() {
        String tooLong = "123456";
        ContractModels.LockedPptSlide slide = slide(
                List.of(block("block-1", "12345"), block("block-2", tooLong)), List.of());
        var result = resolve(slide, profile(List.of(component(
                "component-body", "BODY", List.of(slot("slot-body", "BODY", List.of(BODY), true, 5, null))))));

        assertThat(result.plan().blockToSlotBindings()).containsEntry("block-1", "slot-body");
        assertThat(result.plan().unresolvedBlockIds()).containsExactly("block-2");
        assertThat(result.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("SLOT_CAPACITY_EXCEEDED");
            assertThat(diagnostic.safeDetails()).containsEntry("constraint", "maxCharacters");
        });
        assertThat(slide.contentBlocks().get(1).content()).isEqualTo(tooLong);
    }

    @Test
    void sharedAllocationIsStableForTwentyRunsAndDoesNotMutateInputs() throws Exception {
        ContractModels.LockedPptSlide slide = slide(
                List.of(block("block-1", "one"), block("block-2", "two")),
                List.of(asset("asset-1", IMAGE, ContractTypes.PlacementIntent.IMAGE)));
        ContractModels.ConfirmedTemplateProfile profile = profile(List.of(component("component-complete", "BODY", List.of(
                slot("slot-body-1", "BODY", List.of(BODY), true, 100, 1),
                slot("slot-body-2", "BODY", List.of(BODY), true, 100, 1),
                slot("slot-image", "IMAGE", List.of(ContractTypes.ContentType.IMAGE), true, null, 1)))));
        String before = objectMapper.writeValueAsString(List.of(slide, profile));
        List<String> results = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            results.add(objectMapper.writeValueAsString(resolver.resolve(slide, profile)));
        }

        assertThat(results).allMatch(results.get(0)::equals);
        assertThat(objectMapper.writeValueAsString(List.of(slide, profile))).isEqualTo(before);
    }

    private ComponentResolver.ResolverResult resolve(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile) {
        return resolver.resolve(slide, profile);
    }

    private ContractModels.LockedPptSlide slide(
            List<ContractModels.LockedPptContentBlock> blocks,
            List<ContractModels.LockedPptAssetReference> assets) {
        return new ContractModels.LockedPptSlide(
                "slide-1", 1, "title", "goal", blocks,
                new ContractModels.SemanticLayout(
                        "BODY", List.of(new ContractModels.SemanticRegion("region-1", "BODY", CENTER, 100)),
                        TRANSLATE_ONLY),
                assets, List.of(new ContractModels.ProvenanceEntry(MATERIAL, "material-1")), "");
    }

    private ContractModels.LockedPptContentBlock block(String blockId, String content) {
        return new ContractModels.LockedPptContentBlock(
                blockId, BODY, content, MATERIAL, "material-1", true);
    }

    private ContractModels.LockedPptAssetReference asset(
            String assetId,
            ContractTypes.AssetType assetType,
            ContractTypes.PlacementIntent placementIntent) {
        return new ContractModels.LockedPptAssetReference(
                assetId, assetType, "approved-asset", APPROVED, true, placementIntent);
    }

    private ContractModels.ComponentSlot slot(
            String slotId,
            String semanticRole,
            List<ContractTypes.ContentType> acceptedTypes,
            boolean required,
            Integer maxCharacters,
            Integer maxItems) {
        return new ContractModels.ComponentSlot(
                slotId, semanticRole, acceptedTypes,
                new ContractModels.Bounds(1, 1, 100, 100), required,
                new ContractModels.CapacityConstraint(maxCharacters, maxItems));
    }

    private ContractModels.TemplateComponent component(
            String componentId,
            String semanticRole,
            List<ContractModels.ComponentSlot> slots) {
        return new ContractModels.TemplateComponent(
                componentId, componentId, semanticRole, 1,
                List.of(new ContractModels.StableObjectReference(GROUP, "shape-" + componentId)),
                List.of(), slots, TRANSLATE_ONLY,
                new ContractModels.FixedStyle("style-1", "font-1", "color-1", true),
                true, 0.9, true);
    }

    private ContractModels.ConfirmedTemplateProfile profile(
            List<ContractModels.TemplateComponent> components) {
        List<String> objectIds = components.stream()
                .map(component -> component.shapeRefs().get(0).objectId())
                .toList();
        return new ContractModels.ConfirmedTemplateProfile(
                V1, "profile-1", "template-1", 1, 1, CONFIRMED,
                new ContractModels.PageSize(1000, 1000),
                new ContractModels.SpatialProfile(0, 0, 0, 0),
                List.of(new ContractModels.TemplatePageReference("page-1", 1, "BODY", objectIds)),
                components);
    }
}
