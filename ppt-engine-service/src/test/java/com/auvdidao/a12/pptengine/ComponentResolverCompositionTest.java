package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentResolverCompositionTest {

    private final ComponentResolver resolver = new ComponentResolver();

    @Test
    void profileV1AdapterUsesCompleteTotalOrderAndRecordsSelectionBasis() {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                base.contentBlocks(), base.semanticLayout(), List.of(), base.provenance(), base.notes());
        ContractModels.ConfirmedTemplateProfile baseProfile = ContractFixtures.profile();
        ContractModels.ConfirmedTemplateProfile profile = CompositionTestSupport.withComponents(
                baseProfile, List.of(
                        component("component-wide", "BODY", "slot-wide", 1000, 1.0),
                        component("component-wrong-role", "SIDEBAR", "slot-tiny", 40, 1.0),
                        component("component-minimum", "BODY", "slot-minimum", 100, 0.1)));

        ComponentResolver.ResolverResult result = resolver.resolveForComposition(slide, profile, Map.of());

        assertThat(result.plan().selectedComponentIds()).containsExactly("component-minimum");
        assertThat(result.selections()).containsExactly(new CompositionModels.ComponentSelection(
                ContractTypes.SlotBindingKind.TEXT,
                "block-001",
                "component-minimum",
                "slot-minimum",
                ContractTypes.ComponentSelectionBasis.PROFILE_V1_CONFIDENCE_FALLBACK_TOTAL_ORDER));
        assertThat(result.diagnostics())
                .filteredOn(item -> item.code().equals("COMPONENT_SELECTION_COMPATIBILITY_FALLBACK"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.severity()).isEqualTo(ContractTypes.DiagnosticSeverity.WARNING);
                    assertThat(item.safeDetails().get("selectionBasis"))
                            .isEqualTo("PROFILE_V1_CONFIDENCE_FALLBACK_TOTAL_ORDER");
                    assertThat(item.safeDetails().get("selectedComponentId"))
                            .isEqualTo("component-minimum");
                });
    }

    @Test
    void executionReadyComponentCanBeSelectedWithoutTeacherConfirmation() {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                List.of(base.contentBlocks().get(0)), base.semanticLayout(), List.of(), base.provenance(), base.notes());
        ContractModels.TemplateComponent legacy = ContractFixtures.profile().components().get(0);
        ContractModels.TemplateComponent executionReady = new ContractModels.TemplateComponent(
                "component-execution-ready", "service-owned", "BODY", 1,
                legacy.shapeRefs(), legacy.childComponentIds(), legacy.slots(),
                legacy.transformConstraint(), legacy.fixedStyle(), false, 1.0, false,
                "EXECUTION_READY");
        ContractModels.ConfirmedTemplateProfile profile = CompositionTestSupport.withComponents(
                ContractFixtures.profile(), List.of(executionReady));

        ComponentResolver.ResolverResult result = resolver.resolveForComposition(slide, profile, Map.of());

        assertThat(result.diagnostics())
                .filteredOn(item -> item.code().equals("COMPONENT_MISSING"))
                .isEmpty();
        assertThat(result.plan().selectedComponentIds()).containsExactly("component-execution-ready");
    }

    private ContractModels.TemplateComponent component(
            String componentId,
            String semanticRole,
            String slotId,
            int maxCharacters,
            double confidence) {
        ContractModels.TemplateComponent base = ContractFixtures.profile().components().get(0);
        ContractModels.ComponentSlot baseSlot = base.slots().get(0);
        ContractModels.ComponentSlot slot = new ContractModels.ComponentSlot(
                slotId,
                baseSlot.semanticRole(),
                baseSlot.acceptedContentTypes(),
                baseSlot.bounds(),
                true,
                new ContractModels.CapacityConstraint(maxCharacters, 1));
        return new ContractModels.TemplateComponent(
                componentId,
                componentId,
                semanticRole,
                base.sourceSlide(),
                base.shapeRefs(),
                base.childComponentIds(),
                List.of(slot),
                base.transformConstraint(),
                base.fixedStyle(),
                true,
                confidence,
                true);
    }
}
