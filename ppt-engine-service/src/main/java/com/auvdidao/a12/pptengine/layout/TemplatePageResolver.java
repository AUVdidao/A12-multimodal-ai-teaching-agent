package com.auvdidao.a12.pptengine.layout;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.resolver.StableReferenceSetGate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BULLETS;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.QUOTE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TEXT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.TEMPLATE_PAGE_MISSING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.TEMPLATE_PAGE_RESOLVER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TemplatePageSelectionBasis.EXACT_SEMANTIC_ROLE_STABLE_ORDER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.RESPONSIVE;

/**
 * Selects a template page using a fail-closed feasibility pass followed by a
 * deterministic ordering. It never treats preserve-only objects as slots.
 */
@Component
public class TemplatePageResolver {

    private static final Comparator<PageCandidate> PAGE_ORDER = Comparator
            .comparingInt(PageCandidate::exactExecutableCoverage).reversed()
            .thenComparing(Comparator.comparingInt(PageCandidate::exactTransformCount).reversed())
            .thenComparingLong(PageCandidate::capacitySlack)
            .thenComparingInt(candidate -> candidate.page().sourceSlide())
            .thenComparing(candidate -> candidate.page().pageReferenceId());

    public SelectionResult resolve(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile) {
        List<ContractModels.TemplatePageReference> rolePages = profile.templatePageReferences().stream()
                .filter(Objects::nonNull)
                .filter(page -> Objects.equals(page.semanticRole(), slide.semanticLayout().primaryRole()))
                .toList();
        List<PageCandidate> feasiblePages = rolePages.stream()
                .map(page -> assess(page, slide, profile))
                .filter(PageCandidate::feasible)
                .sorted(PAGE_ORDER)
                .toList();
        List<SelectionDecision> decisions = rolePages.stream()
                .map(page -> assess(page, slide, profile).decision())
                .toList();

        if (feasiblePages.isEmpty()) {
            Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put("semanticRole", slide.semanticLayout().primaryRole());
            if (!rolePages.isEmpty() && hasExecutableDemand(slide)) {
                details.put("reason", hasAnyExecutableComponent(rolePages, profile)
                        ? "noFeasibleExecutablePage" : "noLegalExecutableComponentOrSlot");
                details.put("candidatePageCount", Integer.toString(rolePages.size()));
                details.put("feasiblePageCount", "0");
            }
            return missing(slide, details, decisions);
        }

        if (hasStableIdentityCollision(feasiblePages)) {
            return missing(slide, Map.of(
                    "reason", "feasibleStableIdentityCollision",
                    "feasiblePageCount", Integer.toString(feasiblePages.size())), decisions);
        }

        PageCandidate selected = feasiblePages.get(0);
        return new SelectionResult(
                new CompositionModels.TemplatePageSelection(
                        selected.page().pageReferenceId(), selected.page().sourceSlide(),
                        selected.page().semanticRole(), EXACT_SEMANTIC_ROLE_STABLE_ORDER),
                List.of(), decisions, feasiblePages.stream()
                        .map(candidate -> candidate.page().pageReferenceId()).toList(),
                selected.assignment());
    }

    private PageCandidate assess(
            ContractModels.TemplatePageReference page,
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile) {
        if (page.pageReferenceId() == null || page.pageReferenceId().isBlank()
                || page.sourceSlide() <= 0) {
            return PageCandidate.infeasible(page);
        }

        List<Demand> demands = demands(slide);
        if (demands.isEmpty()) {
            return PageCandidate.feasible(page, 0, 0, 0, List.of());
        }

        Set<String> rejectedFeasibilityReasons = new LinkedHashSet<>();
        Assignment assignment = assign(page, slide, profile, demands, 0,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashSet<>(),
                rejectedFeasibilityReasons, 0, 0);
        if (assignment == null) {
            return PageCandidate.infeasible(page, rejectedFeasibilityReasons);
        }
        return PageCandidate.feasible(page, demands.size(),
                assignment.exactTransformCount(), assignment.capacitySlack(), assignment.demandBindings());
    }

    /**
     * Deterministic bounded matching. A page is feasible only when every
     * required demand gets a distinct compatible slot within its page scope.
     */
    private Assignment assign(
            ContractModels.TemplatePageReference page,
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            List<Demand> demands,
            int index,
            Map<String, Integer> slotOccupancy,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            Set<String> nonReusableComponents,
            Set<String> rejectedFeasibilityReasons,
            int exactTransformCount,
            long capacitySlack) {
        if (index == demands.size()) {
            return new Assignment(exactTransformCount, capacitySlack);
        }

        Demand demand = demands.get(index);
        List<Candidate> candidates = candidates(page, slide, profile, demand, slotOccupancy,
                nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, nonReusableComponents,
                rejectedFeasibilityReasons);
        for (Candidate candidate : candidates) {
            String slotKey = candidate.component().componentId() + "::" + candidate.slot().slotId();
            String componentId = candidate.component().componentId();
            slotOccupancy.merge(slotKey, 1, Integer::sum);
            StableReferenceSetGate.Check referenceCheck = StableReferenceSetGate.inspect(candidate.component(), page,
                    nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, nonReusableComponents);
            referenceCheck.referenceKeys().forEach(key -> nativeReferenceOccupancy.merge(key, 1, Integer::sum));
            if (!candidate.component().reusable()) {
                referenceCheck.referenceKeys().forEach(key ->
                        nonReusableNativeReferenceOccupancy.merge(key, 1, Integer::sum));
            }
            if (!candidate.component().reusable()) {
                nonReusableComponents.add(componentId);
            }
            Assignment result = assign(page, slide, profile, demands, index + 1,
                    slotOccupancy, nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy,
                    nonReusableComponents, rejectedFeasibilityReasons,
                    exactTransformCount + exactTransform(candidate.component(), slide),
                    capacitySlack + capacitySlack(demand, candidate.slot(), slotOccupancy.get(slotKey) - 1));
            if (result != null) {
                return result.prepend(new RequiredDemandAssignment(
                        demand.id(), demand.asset(), candidate.component().componentId(), candidate.slot().slotId()));
            }
            decrement(slotOccupancy, slotKey);
            referenceCheck.referenceKeys().forEach(key -> decrement(nativeReferenceOccupancy, key));
            if (!candidate.component().reusable()) {
                referenceCheck.referenceKeys().forEach(key ->
                        decrement(nonReusableNativeReferenceOccupancy, key));
            }
            if (!candidate.component().reusable()) {
                nonReusableComponents.remove(componentId);
            }
        }
        return null;
    }

    private List<Candidate> candidates(
            ContractModels.TemplatePageReference page,
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            Demand demand,
            Map<String, Integer> slotOccupancy,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            Set<String> nonReusableComponents,
            Set<String> rejectedFeasibilityReasons) {
        List<Candidate> result = new ArrayList<>();
        for (ContractModels.TemplateComponent component : profile.components()) {
            if (component == null || component.sourceSlide() != page.sourceSlide()
                    || component.slots() == null
                    || component.slots().stream().noneMatch(slot -> slot != null && matches(slot, demand))) {
                continue;
            }
            StableReferenceSetGate.Check referenceCheck = StableReferenceSetGate.inspect(component, page,
                    nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, nonReusableComponents);
            if (!referenceCheck.feasible()) {
                rejectedFeasibilityReasons.addAll(referenceCheck.rejectionReasons());
                continue;
            }
            for (ContractModels.ComponentSlot slot : component.slots()) {
                if (slot == null || !matches(slot, demand)
                        || !acceptsContentType(slot, demand.contentType())
                        || !transformAllowed(slide.semanticLayout(), component)
                        || !legalBounds(slot, profile.pageSize())
                        || !characterCapacityAllows(demand.content(), slot)
                        || !itemCapacityAllows(slot, slotOccupancy,
                        component.componentId() + "::" + slot.slotId())) {
                    continue;
                }
                result.add(new Candidate(component, slot));
            }
        }
        result.sort(candidateOrder(slide, demand, slotOccupancy));
        return result;
    }

    private Comparator<Candidate> candidateOrder(
            ContractModels.LockedPptSlide slide,
            Demand demand,
            Map<String, Integer> slotOccupancy) {
        return Comparator
                .comparing((Candidate candidate) ->
                        !candidate.component().semanticRole().equals(slide.semanticLayout().primaryRole()))
                .thenComparingInt(candidate -> demand.content() == null
                        ? Integer.MAX_VALUE : characterSlack(demand.content(), candidate.slot()))
                .thenComparingInt(candidate -> itemRemaining(candidate.slot(), slotOccupancy,
                        candidate.component().componentId() + "::" + candidate.slot().slotId()))
                .thenComparing((Candidate candidate) -> candidate.component().confidence(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.component().componentId())
                .thenComparing(candidate -> candidate.slot().slotId());
    }

    private List<Demand> demands(ContractModels.LockedPptSlide slide) {
        List<Demand> result = new ArrayList<>();
        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            result.add(new Demand(block.type(), block.content(), false, block.blockId()));
        }
        for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
            if (asset.required() || asset.approvalStatus() == ContractTypes.ApprovalStatus.APPROVED) {
                ContractTypes.ContentType contentType = assetType(asset);
                result.add(new Demand(contentType, null, true, asset.assetId()));
            }
        }
        return List.copyOf(result);
    }

    private boolean matches(ContractModels.ComponentSlot slot, Demand demand) {
        if (demand.asset()) {
            return demand.contentType() != null
                    && slot.semanticRole().equals(demand.contentType().name());
        }
        return slotMatchesBlockType(slot, demand.contentType());
    }

    private boolean transformAllowed(
            ContractModels.SemanticLayout layout,
            ContractModels.TemplateComponent component) {
        return layout.requestedTransform() == null
                || component.transformConstraint() == layout.requestedTransform()
                || component.transformConstraint() == RESPONSIVE;
    }

    private int exactTransform(
            ContractModels.TemplateComponent component,
            ContractModels.LockedPptSlide slide) {
        return slide.semanticLayout().requestedTransform() != null
                && component.transformConstraint() == slide.semanticLayout().requestedTransform() ? 1 : 0;
    }

    private long capacitySlack(
            Demand demand,
            ContractModels.ComponentSlot slot,
            int previousOccupancy) {
        long result = characterSlack(demand.content(), slot);
        Integer maxItems = slot.capacityConstraint() == null ? null : slot.capacityConstraint().maxItems();
        if (maxItems != null) {
            result += Math.max(0, maxItems - previousOccupancy - 1L);
        }
        return result;
    }

    private int characterSlack(String content, ContractModels.ComponentSlot slot) {
        Integer max = slot.capacityConstraint() == null ? null : slot.capacityConstraint().maxCharacters();
        return content == null || max == null ? 1_000_000 : Math.max(0, max - content.length());
    }

    private boolean characterCapacityAllows(String content, ContractModels.ComponentSlot slot) {
        Integer max = slot.capacityConstraint() == null ? null : slot.capacityConstraint().maxCharacters();
        return content == null || max == null || content.length() <= max;
    }

    private boolean itemCapacityAllows(
            ContractModels.ComponentSlot slot,
            Map<String, Integer> occupancy,
            String slotKey) {
        Integer max = slot.capacityConstraint() == null ? null : slot.capacityConstraint().maxItems();
        return max == null || occupancy.getOrDefault(slotKey, 0) < max;
    }

    private int itemRemaining(
            ContractModels.ComponentSlot slot,
            Map<String, Integer> occupancy,
            String slotKey) {
        Integer max = slot.capacityConstraint() == null ? null : slot.capacityConstraint().maxItems();
        return max == null ? Integer.MAX_VALUE : max - occupancy.getOrDefault(slotKey, 0);
    }

    private boolean acceptsContentType(
            ContractModels.ComponentSlot slot,
            ContractTypes.ContentType type) {
        return type != null && (slot.acceptedContentTypes().contains(type)
                || (isTextType(type) && slot.acceptedContentTypes().contains(TEXT)));
    }

    private boolean slotMatchesBlockType(
            ContractModels.ComponentSlot slot,
            ContractTypes.ContentType type) {
        return type != null && (slot.semanticRole().equals(type.name())
                || (isTextType(type) && slot.semanticRole().equals(TEXT.name())));
    }

    private boolean isTextType(ContractTypes.ContentType type) {
        return type == ContractTypes.ContentType.TITLE || type == BODY
                || type == BULLETS || type == QUOTE;
    }

    private ContractTypes.ContentType assetType(ContractModels.LockedPptAssetReference asset) {
        return switch (asset.assetType()) {
            case IMAGE -> ContractTypes.ContentType.IMAGE;
            case CHART -> ContractTypes.ContentType.CHART;
            case TABLE -> ContractTypes.ContentType.TABLE;
            case ICON, VIDEO, OTHER -> null;
        };
    }

    private boolean legalBounds(ContractModels.ComponentSlot slot, ContractModels.PageSize page) {
        ContractModels.Bounds bounds = slot.bounds();
        return bounds != null && bounds.leftEmu() >= 0 && bounds.topEmu() >= 0
                && bounds.widthEmu() > 0 && bounds.heightEmu() > 0
                && (long) bounds.leftEmu() + bounds.widthEmu() <= page.widthEmu()
                && (long) bounds.topEmu() + bounds.heightEmu() <= page.heightEmu();
    }

    private boolean isExecutionEligible(ContractModels.TemplateComponent component) {
        return StableReferenceSetGate.isExecutionEligible(component);
    }

    private boolean hasExecutableDemand(ContractModels.LockedPptSlide slide) {
        return !slide.contentBlocks().isEmpty()
                || slide.assetRequirements().stream().anyMatch(ContractModels.LockedPptAssetReference::required);
    }

    private boolean hasAnyExecutableComponent(
            List<ContractModels.TemplatePageReference> pages,
            ContractModels.ConfirmedTemplateProfile profile) {
        return pages.stream().anyMatch(page -> profile.components().stream()
                .anyMatch(component -> component.sourceSlide() == page.sourceSlide()
                        && StableReferenceSetGate.inspect(component, page, Map.of(), Map.of(), Set.of()).feasible()));
    }

    private void decrement(Map<String, Integer> occupancy, String key) {
        int value = occupancy.getOrDefault(key, 0);
        if (value <= 1) {
            occupancy.remove(key);
        } else {
            occupancy.put(key, value - 1);
        }
    }

    private boolean hasStableIdentityCollision(List<PageCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.page().sourceSlide() + "::"
                        + candidate.page().pageReferenceId())
                .distinct().count() != candidates.size();
    }

    private SelectionResult missing(
            ContractModels.LockedPptSlide slide,
            Map<String, String> details,
            List<SelectionDecision> decisions) {
        return new SelectionResult(null, List.of(DiagnosticFactory.stageError(
                TEMPLATE_PAGE_RESOLVER, TEMPLATE_PAGE_MISSING, "templatePage.exactRoleMissing",
                slide.slideId(), slide.pageNumber(), null, null, null, null, details)),
                decisions, List.of(), List.of());
    }

    private record Demand(
            ContractTypes.ContentType contentType,
            String content,
            boolean asset,
            String id) {
    }

    private record Candidate(
            ContractModels.TemplateComponent component,
            ContractModels.ComponentSlot slot) {
    }

    private record Assignment(
            int exactTransformCount,
            long capacitySlack,
            List<RequiredDemandAssignment> demandBindings) {
        private Assignment {
            demandBindings = List.copyOf(demandBindings);
        }

        private Assignment(int exactTransformCount, long capacitySlack) {
            this(exactTransformCount, capacitySlack, List.of());
        }

        private Assignment prepend(RequiredDemandAssignment binding) {
            List<RequiredDemandAssignment> bindings = new ArrayList<>();
            bindings.add(binding);
            bindings.addAll(demandBindings);
            return new Assignment(exactTransformCount, capacitySlack, bindings);
        }
    }

    private record PageCandidate(
            ContractModels.TemplatePageReference page,
            boolean feasible,
            int exactExecutableCoverage,
            int exactTransformCount,
            long capacitySlack,
            List<String> rejectionReasons,
            List<RequiredDemandAssignment> assignment) {
        private static PageCandidate infeasible(ContractModels.TemplatePageReference page) {
            return new PageCandidate(page, false, 0, 0, Long.MAX_VALUE, List.of(), List.of());
        }

        private static PageCandidate infeasible(
                ContractModels.TemplatePageReference page,
                Set<String> rejectionReasons) {
            return new PageCandidate(page, false, 0, 0, Long.MAX_VALUE,
                    List.copyOf(rejectionReasons), List.of());
        }

        private static PageCandidate feasible(
            ContractModels.TemplatePageReference page,
            int coverage,
            int transformCount,
            long slack,
            List<RequiredDemandAssignment> assignment) {
            return new PageCandidate(page, true, coverage, transformCount, slack,
                    List.of(), List.copyOf(assignment));
        }

        private SelectionDecision decision() {
            return new SelectionDecision(page.pageReferenceId(), feasible,
                    feasible ? List.of() : rejectionReasons.isEmpty()
                            ? List.of("REQUIRED_DEMAND_NOT_FEASIBLE") : rejectionReasons);
        }
    }

    public record SelectionResult(
            CompositionModels.TemplatePageSelection selection,
            List<ContractModels.Diagnostic> diagnostics,
            List<SelectionDecision> decisions,
            List<String> rankedFeasiblePageReferenceIds,
            List<RequiredDemandAssignment> assignment) {
        public SelectionResult(
                CompositionModels.TemplatePageSelection selection,
                List<ContractModels.Diagnostic> diagnostics) {
            this(selection, diagnostics, List.of(), List.of(), List.of());
        }

        public SelectionResult {
            diagnostics = List.copyOf(diagnostics);
            decisions = List.copyOf(decisions);
            rankedFeasiblePageReferenceIds = List.copyOf(rankedFeasiblePageReferenceIds);
            assignment = List.copyOf(assignment);
        }
    }

    public record RequiredDemandAssignment(
            String demandId,
            boolean asset,
            String componentId,
            String slotId) {
    }

    public record SelectionDecision(
            String pageReferenceId,
            boolean feasible,
            List<String> rejectionReasons) {
        public SelectionDecision {
            rejectionReasons = List.copyOf(rejectionReasons);
        }
    }
}
