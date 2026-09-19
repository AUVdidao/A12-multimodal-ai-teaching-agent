package com.auvdidao.a12.pptengine.layout;

import com.auvdidao.a12.pptengine.composition.StablePlanIdFactory;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.COMPOSITION_REFERENCE_INVALID;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.LAYOUT_OUT_OF_BOUNDS;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.SAFE_AREA_VIOLATION;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.SEMANTIC_REGION_CAPACITY_EXCEEDED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.LAYOUT_RESOLVER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.FULL_BLEED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SlotBindingKind.ASSET;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SlotBindingKind.TEXT;

/**
 * Converts confirmed slot bounds into a deterministic layout. No coordinate is
 * invented: every placement copies one ComponentSlot bounds value verbatim.
 */
@Component
public class LayoutResolver {

    private final StablePlanIdFactory idFactory;
    private final LayoutVariantCompiler variantCompiler;

    public LayoutResolver(StablePlanIdFactory idFactory) {
        this(idFactory, new LayoutVariantCompiler());
    }

    @Autowired
    public LayoutResolver(StablePlanIdFactory idFactory, LayoutVariantCompiler variantCompiler) {
        this.idFactory = idFactory;
        this.variantCompiler = variantCompiler;
    }

    public LayoutResult resolve(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.ResolvedSlidePlan resolverPlan,
            CompositionModels.TemplatePageSelection templatePage) {
        return resolve(slide, profile, resolverPlan, List.of(), templatePage);
    }

    public LayoutResult resolveForComposition(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            ComponentResolver.ResolverResult resolverResult,
            CompositionModels.TemplatePageSelection templatePage) {
        return resolve(slide, profile, resolverResult.plan(), resolverResult.selections(), templatePage);
    }

    private LayoutResult resolve(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.ResolvedSlidePlan resolverPlan,
            List<CompositionModels.ComponentSelection> componentSelections,
            CompositionModels.TemplatePageSelection templatePage) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        ContractModels.Bounds safeArea = safeArea(profile, slide, diagnostics);
        if (safeArea == null) {
            return new LayoutResult(null, diagnostics);
        }

        Map<String, ContractModels.TemplateComponent> components = new LinkedHashMap<>();
        Map<String, SlotOwner> slots = new LinkedHashMap<>();
        for (ContractModels.TemplateComponent component : profile.components()) {
            components.putIfAbsent(component.componentId(), component);
            for (ContractModels.ComponentSlot slot : component.slots()) {
                slots.putIfAbsent(slot.slotId(), new SlotOwner(component, slot));
            }
        }

        List<CompositionModels.ResolvedComponentPlacement> componentPlacements = new ArrayList<>();
        for (String componentId : resolverPlan.selectedComponentIds().stream().sorted().toList()) {
            ContractModels.TemplateComponent component = components.get(componentId);
            if (component == null) {
                diagnostics.add(referenceError(slide, null, null, componentId, null, "component"));
                continue;
            }
            componentPlacements.add(new CompositionModels.ResolvedComponentPlacement(
                    component.componentId(), component.shapeRefs().stream()
                    .map(shape -> new CompositionModels.StableNativeObjectReference(
                            ContractTypes.STABLE_NATIVE_OBJECT_REFERENCE_V1,
                            profile.templateId(), profile.templateVersion(),
                            ContractTypes.NativeObjectScope.SLIDE,
                            component.sourceSlide(), shape.objectId(), shape.objectType()))
                    .toList(),
                    component.transformConstraint()));
        }

        Set<String> selectedComponentIds = Set.copyOf(resolverPlan.selectedComponentIds());
        RegionAllocator regionAllocator = new RegionAllocator(slide.semanticLayout().regions());
        List<CompositionModels.SlotPlacement> slotPlacements = new ArrayList<>();

        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            String slotId = resolverPlan.blockToSlotBindings().get(block.blockId());
            if (slotId != null) {
                addPlacement(slide, profile, safeArea, selectedComponentIds, slots, regionAllocator,
                        TEXT, block.blockId(), slotId, block.blockId(), null,
                        slotPlacements, diagnostics);
            }
        }
        for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
            String slotId = resolverPlan.assetToSlotBindings().get(asset.assetId());
            if (slotId != null) {
                addPlacement(slide, profile, safeArea, selectedComponentIds, slots, regionAllocator,
                        ASSET, asset.assetId(), slotId, null, asset.assetId(),
                        slotPlacements, diagnostics);
            }
        }

        CompositionModels.ResolvedLayoutPlan plan = new CompositionModels.ResolvedLayoutPlan(
                slide.slideId(), slide.pageNumber(), profile.pageSize(), safeArea, templatePage,
                componentPlacements, componentSelections, slotPlacements);
        return new LayoutResult(plan, diagnostics);
    }

    private void addPlacement(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.Bounds safeArea,
            Set<String> selectedComponentIds,
            Map<String, SlotOwner> slots,
            RegionAllocator regionAllocator,
            ContractTypes.SlotBindingKind bindingKind,
            String bindingId,
            String slotId,
            String blockId,
            String assetId,
            List<CompositionModels.SlotPlacement> placements,
            List<ContractModels.Diagnostic> diagnostics) {
        SlotOwner owner = slots.get(slotId);
        if (owner == null || !selectedComponentIds.contains(owner.component().componentId())) {
            diagnostics.add(referenceError(
                    slide, blockId, assetId,
                    owner == null ? null : owner.component().componentId(), slotId, "slotBinding"));
            return;
        }

        RegionSelection region = regionAllocator.allocate(owner.slot().semanticRole());
        if (region.capacityExceeded()) {
            diagnostics.add(DiagnosticFactory.stageError(
                    LAYOUT_RESOLVER,
                    SEMANTIC_REGION_CAPACITY_EXCEEDED,
                    "layout.semanticRegionCapacityExceeded",
                    slide.slideId(), slide.pageNumber(), blockId, assetId,
                    owner.component().componentId(), slotId,
                    Map.of("semanticRole", owner.slot().semanticRole())));
            return;
        }

        ContractModels.Bounds bounds = variantCompiler.boundsFor(
                slide, profile, owner.slot(), bindingKind, bindingId, safeArea);
        if (!insidePage(bounds, profile.pageSize())) {
            diagnostics.add(DiagnosticFactory.stageError(
                    LAYOUT_RESOLVER,
                    LAYOUT_OUT_OF_BOUNDS,
                    "layout.outOfBounds",
                    slide.slideId(), slide.pageNumber(), blockId, assetId,
                    owner.component().componentId(), slotId,
                    boundsDetails(bounds)));
            return;
        }
        if (!region.fullBleed() && !inside(bounds, safeArea)) {
            diagnostics.add(DiagnosticFactory.stageError(
                    LAYOUT_RESOLVER,
                    SAFE_AREA_VIOLATION,
                    "layout.safeAreaViolation",
                    slide.slideId(), slide.pageNumber(), blockId, assetId,
                    owner.component().componentId(), slotId,
                    boundsDetails(bounds)));
            return;
        }

        placements.add(new CompositionModels.SlotPlacement(
                idFactory.placementId(slide.slideId(), bindingKind, bindingId,
                        owner.component().componentId(), slotId),
                owner.component().componentId(),
                slotId,
                bindingKind,
                bindingId,
                owner.slot().semanticRole(),
                region.regionId(),
                bounds,
                slide.semanticLayout().requestedTransform(),
                owner.component().transformConstraint()));
    }

    private ContractModels.Bounds safeArea(
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.LockedPptSlide slide,
            List<ContractModels.Diagnostic> diagnostics) {
        ContractModels.PageSize page = profile.pageSize();
        ContractModels.SpatialProfile spatial = profile.spatialProfile();
        long width = (long) page.widthEmu() - spatial.safeMarginLeftEmu() - spatial.safeMarginRightEmu();
        long height = (long) page.heightEmu() - spatial.safeMarginTopEmu() - spatial.safeMarginBottomEmu();
        if (width <= 0 || height <= 0) {
            diagnostics.add(DiagnosticFactory.stageError(
                    LAYOUT_RESOLVER,
                    SAFE_AREA_VIOLATION,
                    "layout.safeAreaInvalid",
                    slide.slideId(), slide.pageNumber(), null, null, null, null,
                    Map.of("reason", "marginsExhaustPage")));
            return null;
        }
        return new ContractModels.Bounds(
                spatial.safeMarginLeftEmu(), spatial.safeMarginTopEmu(), (int) width, (int) height);
    }

    private boolean insidePage(ContractModels.Bounds bounds, ContractModels.PageSize page) {
        return bounds.leftEmu() >= 0
                && bounds.topEmu() >= 0
                && bounds.widthEmu() > 0
                && bounds.heightEmu() > 0
                && (long) bounds.leftEmu() + bounds.widthEmu() <= page.widthEmu()
                && (long) bounds.topEmu() + bounds.heightEmu() <= page.heightEmu();
    }

    private boolean inside(ContractModels.Bounds inner, ContractModels.Bounds outer) {
        return inner.leftEmu() >= outer.leftEmu()
                && inner.topEmu() >= outer.topEmu()
                && (long) inner.leftEmu() + inner.widthEmu()
                <= (long) outer.leftEmu() + outer.widthEmu()
                && (long) inner.topEmu() + inner.heightEmu()
                <= (long) outer.topEmu() + outer.heightEmu();
    }

    private Map<String, String> boundsDetails(ContractModels.Bounds bounds) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("leftEmu", Integer.toString(bounds.leftEmu()));
        details.put("topEmu", Integer.toString(bounds.topEmu()));
        details.put("widthEmu", Integer.toString(bounds.widthEmu()));
        details.put("heightEmu", Integer.toString(bounds.heightEmu()));
        return details;
    }

    private ContractModels.Diagnostic referenceError(
            ContractModels.LockedPptSlide slide,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            String reference) {
        return DiagnosticFactory.stageError(
                LAYOUT_RESOLVER,
                COMPOSITION_REFERENCE_INVALID,
                "layout.referenceInvalid",
                slide.slideId(), slide.pageNumber(), blockId, assetId, componentId, slotId,
                Map.of("reference", reference));
    }

    private record SlotOwner(
            ContractModels.TemplateComponent component,
            ContractModels.ComponentSlot slot) {
    }

    private static final class RegionAllocator {
        private final List<ContractModels.SemanticRegion> regions;
        private final Map<String, Integer> usage = new HashMap<>();

        private RegionAllocator(List<ContractModels.SemanticRegion> regions) {
            this.regions = List.copyOf(regions);
        }

        private RegionSelection allocate(String semanticRole) {
            boolean hasMatchingRegion = false;
            for (ContractModels.SemanticRegion region : regions) {
                if (!region.semanticRole().equals(semanticRole)) {
                    continue;
                }
                hasMatchingRegion = true;
                int used = usage.getOrDefault(region.regionId(), 0);
                if (used < region.maxItems()) {
                    usage.put(region.regionId(), used + 1);
                    return new RegionSelection(
                            region.regionId(), region.preferredPosition() == FULL_BLEED, false);
                }
            }
            return hasMatchingRegion
                    ? new RegionSelection(null, false, true)
                    : new RegionSelection(null, false, false);
        }
    }

    private record RegionSelection(
            String regionId,
            boolean fullBleed,
            boolean capacityExceeded) {
    }

    public record LayoutResult(
            CompositionModels.ResolvedLayoutPlan plan,
            List<ContractModels.Diagnostic> diagnostics) {
        public LayoutResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
