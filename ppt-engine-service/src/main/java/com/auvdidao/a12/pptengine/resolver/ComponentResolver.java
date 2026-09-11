package com.auvdidao.a12.pptengine.resolver;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.layout.TemplatePageResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_TYPE_UNSUPPORTED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_REQUIREMENT_MISSING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.COMPONENT_MISSING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.COMPONENT_SELECTION_COMPATIBILITY_FALLBACK;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.CONTENT_OVERFLOW;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.IMAGE_NOT_APPROVED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.REQUIRED_SLOT_UNFILLED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.SLOT_CAPACITY_EXCEEDED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.SLOT_INCOMPATIBLE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.TRANSFORM_NOT_ALLOWED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.RESPONSIVE;

/**
 * Pure deterministic resolver. It receives immutable contract values and only
 * returns a plan plus diagnostics; it never changes the specification/profile.
 */
@Component
public class ComponentResolver {

    public ResolverResult resolve(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile) {
        return resolve(slide, profile, null, false, null, null, List.of());
    }

    public ResolverResult resolveForComposition(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries) {
        return resolve(slide, profile, Map.copyOf(manifestEntries), true, null, null, List.of());
    }

    public ResolverResult resolveForComposition(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            CompositionModels.TemplatePageSelection templatePage) {
        return resolveForComposition(slide, profile, manifestEntries, templatePage, List.of());
    }

    public ResolverResult resolveForComposition(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            CompositionModels.TemplatePageSelection templatePage,
            List<TemplatePageResolver.RequiredDemandAssignment> assignment) {
        String pageReferenceId = templatePage == null ? null : templatePage.pageReferenceId();
        return resolve(slide, profile, Map.copyOf(manifestEntries), true,
                templatePage == null ? null : templatePage.sourceSlide(), pageReferenceId, assignment);
    }

    private ResolverResult resolve(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> manifestEntries,
            boolean compositionMode,
            Integer sourceSlideFilter,
            String pageReferenceId,
            List<TemplatePageResolver.RequiredDemandAssignment> assignment) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        Map<String, ContractModels.TemplateComponent> selectedComponents = new LinkedHashMap<>();
        Map<String, Integer> slotOccupancy = new HashMap<>();
        Map<String, Integer> nativeReferenceOccupancy = new HashMap<>();
        Map<String, Integer> nonReusableNativeReferenceOccupancy = new HashMap<>();
        Map<String, String> blockBindings = new LinkedHashMap<>();
        Map<String, String> assetBindings = new LinkedHashMap<>();
        List<String> unresolvedBlocks = new ArrayList<>();
        List<String> unresolvedAssets = new ArrayList<>();
        List<CompositionModels.ComponentSelection> selections = new ArrayList<>();
        Set<String> nonReusableComponents = new HashSet<>();
        Map<String, TemplatePageResolver.RequiredDemandAssignment> assignmentByDemand = assignment.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TemplatePageResolver.RequiredDemandAssignment::demandId, item -> item));

        // Frozen traversal order: specification content blocks first, in array order.
        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            SearchResult search = compositionMode
                    ? findBlockCandidateForComposition(slide, block, profile, slotOccupancy,
                    sourceSlideFilter, pageReferenceId, nonReusableComponents,
                    nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, assignmentByDemand)
                    : findBlockCandidate(slide, block, profile, slotOccupancy, nonReusableComponents,
                    nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy);
            if (search.candidate() == null) {
                unresolvedBlocks.add(block.blockId());
                diagnostics.add(blockDiagnostic(slide, block, search));
            } else {
                bind(search.candidate(), selectedComponents, slotOccupancy, nonReusableComponents,
                        nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, profile, pageReferenceId);
                blockBindings.put(block.blockId(), search.candidate().slot().slotId());
                if (compositionMode) {
                    addCompositionSelection(slide, ContractTypes.SlotBindingKind.TEXT, block.blockId(),
                            search.candidate(), selections, diagnostics);
                }
            }
        }

        // Assets share exactly the same per-slide slot occupancy, after all blocks.
        for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
            if (manifestEntries != null) {
                CompositionModels.ApprovedAssetManifestEntry entry = manifestEntries.get(asset.assetId());
                if (entry == null) {
                    unresolvedAssets.add(asset.assetId());
                    diagnostics.add(DiagnosticFactory.resolverError(
                            ASSET_MANIFEST_REQUIREMENT_MISSING, "resolver.manifestEntryMissing",
                            slide.slideId(), slide.pageNumber(), null, asset.assetId(), null, null,
                            Map.of("scope", "assetRequirementId")));
                    continue;
                }
                if (entry.resolution() == ContractTypes.AssetResolution.APPROVED_OMISSION) {
                    continue;
                }
            } else if (asset.approvalStatus() != ContractTypes.ApprovalStatus.APPROVED) {
                unresolvedAssets.add(asset.assetId());
                diagnostics.add(DiagnosticFactory.resolverWarning(
                        IMAGE_NOT_APPROVED, "asset.optionalNotApproved", slide.slideId(), slide.pageNumber(),
                        asset.assetId(), Map.of("required", Boolean.toString(asset.required()))));
                continue;
            }

            ContractTypes.ContentType contentType = contentType(asset.assetType());
            if (contentType == null) {
                unresolvedAssets.add(asset.assetId());
                diagnostics.add(DiagnosticFactory.resolverError(
                        ASSET_TYPE_UNSUPPORTED, "resolver.assetTypeUnsupported",
                        slide.slideId(), slide.pageNumber(), null, asset.assetId(), null, null,
                        Map.of("assetType", asset.assetType().name())));
                continue;
            }

            SearchResult search = compositionMode
                    ? findAssetCandidateForComposition(
                    slide, asset, contentType, profile, slotOccupancy, sourceSlideFilter, pageReferenceId,
                    nonReusableComponents, nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy,
                    assignmentByDemand)
                    : findAssetCandidate(slide, asset, contentType, profile, slotOccupancy,
                    nonReusableComponents, nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy);
            if (search.candidate() == null) {
                unresolvedAssets.add(asset.assetId());
                diagnostics.add(assetDiagnostic(slide, asset, search));
            } else {
                bind(search.candidate(), selectedComponents, slotOccupancy, nonReusableComponents,
                        nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, profile, pageReferenceId);
                assetBindings.put(asset.assetId(), search.candidate().slot().slotId());
                if (compositionMode) {
                    addCompositionSelection(slide, ContractTypes.SlotBindingKind.ASSET, asset.assetId(),
                            search.candidate(), selections, diagnostics);
                }
            }
        }

        addRequiredSlotDiagnostics(slide, selectedComponents, slotOccupancy, diagnostics);

        ContractModels.ResolvedSlidePlan plan = new ContractModels.ResolvedSlidePlan(
                slide.slideId(), slide.pageNumber(), selectedComponents.keySet().stream().sorted().toList(),
                blockBindings, assetBindings, unresolvedBlocks, unresolvedAssets);
        return new ResolverResult(plan, List.copyOf(diagnostics), List.copyOf(selections));
    }

    /**
     * Compose v2 adapter: Profile v1 has no resolverPriority, so every legal
     * candidate is totally ordered by exact component role, minimum sufficient
     * capacity, confirmed confidence, componentId and slotId.
     */
    private SearchResult findBlockCandidateForComposition(
            ContractModels.LockedPptSlide slide,
            ContractModels.LockedPptContentBlock block,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, Integer> slotOccupancy,
            Integer sourceSlideFilter,
            String pageReferenceId,
            Set<String> nonReusableComponents,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            Map<String, TemplatePageResolver.RequiredDemandAssignment> assignmentByDemand) {
        TemplatePageResolver.RequiredDemandAssignment assignment = assignmentByDemand.get(block.blockId());
        if (assignment != null) {
            Candidate candidate = assignedCandidate(profile, assignment, pageReferenceId,
                    nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, nonReusableComponents);
            if (candidate == null) {
                return SearchResult.failure(COMPONENT_MISSING, null, null);
            }
            if (!acceptsContentType(candidate.slot(), block.type())) {
                return SearchResult.failure(SLOT_INCOMPATIBLE, candidate, null);
            }
            if (!transformAllowed(slide.semanticLayout(), candidate.component())) {
                return SearchResult.failure(TRANSFORM_NOT_ALLOWED, candidate, null);
            }
            if (!characterCapacityAllows(block.content(), candidate.slot())) {
                return SearchResult.failure(CONTENT_OVERFLOW, candidate, "maxCharacters");
            }
            if (!itemCapacityAllows(candidate, slotOccupancy)) {
                return SearchResult.failure(SLOT_CAPACITY_EXCEEDED, candidate, "maxItems");
            }
            return SearchResult.success(candidate);
        }
        List<Candidate> roleMatches = roleCandidates(
                profile, block.type(), sourceSlideFilter, pageReferenceId, nonReusableComponents,
                nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, true);
        if (roleMatches.isEmpty()) {
            return SearchResult.failure(COMPONENT_MISSING, null, null);
        }
        List<Candidate> typeCompatible = roleMatches.stream()
                .filter(candidate -> acceptsContentType(candidate.slot(), block.type()))
                .toList();
        if (typeCompatible.isEmpty()) {
            return SearchResult.failure(SLOT_INCOMPATIBLE,
                    firstStable(roleMatches, compositionComparator(slide, block.content(), slotOccupancy)), null);
        }
        List<Candidate> transformCompatible = typeCompatible.stream()
                .filter(candidate -> transformAllowed(slide.semanticLayout(), candidate.component()))
                .toList();
        if (transformCompatible.isEmpty()) {
            return SearchResult.failure(TRANSFORM_NOT_ALLOWED,
                    firstStable(typeCompatible, compositionComparator(slide, block.content(), slotOccupancy)), null);
        }
        List<Candidate> characterCompatible = transformCompatible.stream()
                .filter(candidate -> characterCapacityAllows(block.content(), candidate.slot()))
                .toList();
        if (characterCompatible.isEmpty()) {
            return SearchResult.failure(CONTENT_OVERFLOW,
                    firstStable(transformCompatible,
                            compositionComparator(slide, block.content(), slotOccupancy)),
                    "maxCharacters");
        }
        List<Candidate> itemCompatible = characterCompatible.stream()
                .filter(candidate -> itemCapacityAllows(candidate, slotOccupancy))
                .toList();
        if (itemCompatible.isEmpty()) {
            return SearchResult.failure(SLOT_CAPACITY_EXCEEDED,
                    firstStable(characterCompatible,
                            compositionComparator(slide, block.content(), slotOccupancy)),
                    "maxItems");
        }
        return SearchResult.success(firstStable(
                itemCompatible, compositionComparator(slide, block.content(), slotOccupancy)));
    }

    private SearchResult findAssetCandidateForComposition(
            ContractModels.LockedPptSlide slide,
            ContractModels.LockedPptAssetReference asset,
            ContractTypes.ContentType contentType,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, Integer> slotOccupancy,
            Integer sourceSlideFilter,
            String pageReferenceId,
            Set<String> nonReusableComponents,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            Map<String, TemplatePageResolver.RequiredDemandAssignment> assignmentByDemand) {
        TemplatePageResolver.RequiredDemandAssignment assignment = assignmentByDemand.get(asset.assetId());
        if (assignment != null) {
            Candidate candidate = assignedCandidate(profile, assignment, pageReferenceId,
                    nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, nonReusableComponents);
            if (candidate == null) {
                return SearchResult.failure(COMPONENT_MISSING, null, null);
            }
            if (!candidate.slot().acceptedContentTypes().contains(contentType)) {
                return SearchResult.failure(SLOT_INCOMPATIBLE, candidate, null);
            }
            if (!transformAllowed(slide.semanticLayout(), candidate.component())) {
                return SearchResult.failure(TRANSFORM_NOT_ALLOWED, candidate, null);
            }
            if (!itemCapacityAllows(candidate, slotOccupancy)) {
                return SearchResult.failure(SLOT_CAPACITY_EXCEEDED, candidate, "maxItems");
            }
            return SearchResult.success(candidate);
        }
        List<Candidate> roleMatches = roleCandidates(
                profile, asset.placementIntent().name(), sourceSlideFilter, pageReferenceId,
                nonReusableComponents, nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, true);
        if (roleMatches.isEmpty()) {
            return SearchResult.failure(COMPONENT_MISSING, null, null);
        }
        List<Candidate> typeCompatible = roleMatches.stream()
                .filter(candidate -> candidate.slot().acceptedContentTypes().contains(contentType))
                .toList();
        if (typeCompatible.isEmpty()) {
            return SearchResult.failure(SLOT_INCOMPATIBLE,
                    firstStable(roleMatches, compositionComparator(slide, null, slotOccupancy)), null);
        }
        List<Candidate> transformCompatible = typeCompatible.stream()
                .filter(candidate -> transformAllowed(slide.semanticLayout(), candidate.component()))
                .toList();
        if (transformCompatible.isEmpty()) {
            return SearchResult.failure(TRANSFORM_NOT_ALLOWED,
                    firstStable(typeCompatible, compositionComparator(slide, null, slotOccupancy)), null);
        }
        List<Candidate> itemCompatible = transformCompatible.stream()
                .filter(candidate -> itemCapacityAllows(candidate, slotOccupancy))
                .toList();
        if (itemCompatible.isEmpty()) {
            return SearchResult.failure(SLOT_CAPACITY_EXCEEDED,
                    firstStable(transformCompatible, compositionComparator(slide, null, slotOccupancy)),
                    "maxItems");
        }
        return SearchResult.success(firstStable(
                itemCompatible, compositionComparator(slide, null, slotOccupancy)));
    }

    private Candidate assignedCandidate(
            ContractModels.ConfirmedTemplateProfile profile,
            TemplatePageResolver.RequiredDemandAssignment assignment,
            String pageReferenceId,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            Set<String> nonReusableComponents) {
        ContractModels.TemplatePageReference page = page(profile, pageReferenceId);
        if (pageReferenceId != null && page == null) {
            return null;
        }
        for (ContractModels.TemplateComponent component : profile.components()) {
            if (!assignment.componentId().equals(component.componentId())) {
                continue;
            }
            StableReferenceSetGate.Check referenceCheck = StableReferenceSetGate.inspect(
                    component, page, nativeReferenceOccupancy,
                    nonReusableNativeReferenceOccupancy, nonReusableComponents);
            if (!referenceCheck.feasible()) {
                return null;
            }
            for (ContractModels.ComponentSlot slot : component.slots()) {
                if (slot != null && assignment.slotId().equals(slot.slotId())) {
                    return new Candidate(component, slot);
                }
            }
        }
        return null;
    }

    private List<Candidate> roleCandidates(
            ContractModels.ConfirmedTemplateProfile profile,
            String slotSemanticRole,
            Integer sourceSlideFilter,
            String pageReferenceId,
            Set<String> nonReusableComponents,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            boolean requireLegalBounds) {
        ContractModels.TemplatePageReference page = page(profile, pageReferenceId);
        if (pageReferenceId != null && page == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ContractModels.TemplateComponent component : profile.components()) {
            if (!StableReferenceSetGate.inspect(component, page, nativeReferenceOccupancy,
                    nonReusableNativeReferenceOccupancy, nonReusableComponents).feasible()
                    || (sourceSlideFilter != null && component.sourceSlide() != sourceSlideFilter)) {
                continue;
            }
            for (ContractModels.ComponentSlot slot : component.slots()) {
                if (slot != null && slot.slotId() != null && !slot.slotId().isBlank()
                        && slot.semanticRole().equals(slotSemanticRole)
                        && (!requireLegalBounds || legalBounds(slot, profile.pageSize()))) {
                    candidates.add(new Candidate(component, slot));
                }
            }
        }
        return List.copyOf(candidates);
    }

    private List<Candidate> roleCandidates(
            ContractModels.ConfirmedTemplateProfile profile,
            ContractTypes.ContentType contentType,
            Integer sourceSlideFilter,
            String pageReferenceId,
            Set<String> nonReusableComponents,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            boolean requireLegalBounds) {
        ContractModels.TemplatePageReference page = page(profile, pageReferenceId);
        if (pageReferenceId != null && page == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ContractModels.TemplateComponent component : profile.components()) {
            if (!StableReferenceSetGate.inspect(component, page, nativeReferenceOccupancy,
                    nonReusableNativeReferenceOccupancy, nonReusableComponents).feasible()
                    || (sourceSlideFilter != null && component.sourceSlide() != sourceSlideFilter)) {
                continue;
            }
            for (ContractModels.ComponentSlot slot : component.slots()) {
                if (slot != null && slot.slotId() != null && !slot.slotId().isBlank()
                        && slotMatchesBlockType(slot, contentType)
                        && (!requireLegalBounds || legalBounds(slot, profile.pageSize()))) {
                    candidates.add(new Candidate(component, slot));
                }
            }
        }
        return List.copyOf(candidates);
    }

    private ContractModels.TemplatePageReference page(
            ContractModels.ConfirmedTemplateProfile profile,
            String pageReferenceId) {
        if (pageReferenceId == null) {
            return null;
        }
        return profile.templatePageReferences().stream()
                .filter(page -> pageReferenceId.equals(page.pageReferenceId()))
                .findFirst()
                .orElse(null);
    }

    private boolean legalBounds(
            ContractModels.ComponentSlot slot,
            ContractModels.PageSize page) {
        ContractModels.Bounds bounds = slot.bounds();
        return bounds != null && page != null
                && bounds.leftEmu() >= 0 && bounds.topEmu() >= 0
                && bounds.widthEmu() > 0 && bounds.heightEmu() > 0
                && (long) bounds.leftEmu() + bounds.widthEmu() <= page.widthEmu()
                && (long) bounds.topEmu() + bounds.heightEmu() <= page.heightEmu();
    }

    private Candidate firstStable(List<Candidate> candidates, Comparator<Candidate> comparator) {
        return candidates.stream().min(comparator).orElse(null);
    }

    private Comparator<Candidate> compositionComparator(
            ContractModels.LockedPptSlide slide,
            String content,
            Map<String, Integer> slotOccupancy) {
        return Comparator
                .comparing((Candidate candidate) ->
                        !candidate.component().semanticRole().equals(slide.semanticLayout().primaryRole()))
                .thenComparingInt(candidate -> characterCapacitySlack(content, candidate.slot()))
                .thenComparingInt(candidate -> itemCapacityRemaining(candidate, slotOccupancy))
                .thenComparing((Candidate candidate) -> candidate.component().confidence(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.component().componentId())
                .thenComparing(candidate -> candidate.slot().slotId());
    }

    private int characterCapacitySlack(String content, ContractModels.ComponentSlot slot) {
        if (content == null || slot.capacityConstraint() == null
                || slot.capacityConstraint().maxCharacters() == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, slot.capacityConstraint().maxCharacters() - content.length());
    }

    private int itemCapacityRemaining(
            Candidate candidate,
            Map<String, Integer> slotOccupancy) {
        ContractModels.ComponentSlot slot = candidate.slot();
        if (slot.capacityConstraint() == null || slot.capacityConstraint().maxItems() == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, slot.capacityConstraint().maxItems()
                - slotOccupancy.getOrDefault(slotKey(candidate), 0));
    }

    private void addCompositionSelection(
            ContractModels.LockedPptSlide slide,
            ContractTypes.SlotBindingKind bindingKind,
            String bindingId,
            Candidate candidate,
            List<CompositionModels.ComponentSelection> selections,
            List<ContractModels.Diagnostic> diagnostics) {
        ContractTypes.ComponentSelectionBasis basis =
                ContractTypes.ComponentSelectionBasis.PROFILE_V1_CONFIDENCE_FALLBACK_TOTAL_ORDER;
        selections.add(new CompositionModels.ComponentSelection(
                bindingKind, bindingId, candidate.component().componentId(),
                candidate.slot().slotId(), basis));
        diagnostics.add(DiagnosticFactory.stageWarning(
                ContractTypes.DiagnosticSource.COMPONENT_RESOLVER,
                COMPONENT_SELECTION_COMPATIBILITY_FALLBACK,
                "resolver.profileV1PriorityFallback",
                slide.slideId(), slide.pageNumber(),
                bindingKind == ContractTypes.SlotBindingKind.TEXT ? bindingId : null,
                bindingKind == ContractTypes.SlotBindingKind.ASSET ? bindingId : null,
                candidate.component().componentId(), candidate.slot().slotId(),
                Map.of("selectionBasis", basis.name(),
                        "selectedComponentId", candidate.component().componentId())));
    }

    private SearchResult findBlockCandidate(
            ContractModels.LockedPptSlide slide,
            ContractModels.LockedPptContentBlock block,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, Integer> slotOccupancy,
            Set<String> nonReusableComponents,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy) {
        Candidate firstRoleMatch = null;
        Candidate firstTransformMismatch = null;
        Candidate firstCapacityMismatch = null;
        String capacityConstraint = null;
        List<Candidate> roleMatches = roleCandidates(profile, block.type(), null, null,
                nonReusableComponents, nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, false);
        List<Candidate> typeCompatible = roleMatches.stream()
                .filter(candidate -> acceptsContentType(candidate.slot(), block.type())).toList();
        List<Candidate> transformCompatible = typeCompatible.stream()
                .filter(candidate -> transformAllowed(slide.semanticLayout(), candidate.component())).toList();
        List<Candidate> characterCompatible = transformCompatible.stream()
                .filter(candidate -> characterCapacityAllows(block.content(), candidate.slot())).toList();
        List<Candidate> feasible = characterCompatible.stream()
                .filter(candidate -> itemCapacityAllows(candidate, slotOccupancy)).toList();
        firstRoleMatch = firstStable(roleMatches, compositionComparator(slide, block.content(), slotOccupancy));
        firstTransformMismatch = firstStable(typeCompatible.stream()
                .filter(candidate -> !transformAllowed(slide.semanticLayout(), candidate.component())).toList(),
                compositionComparator(slide, block.content(), slotOccupancy));
        firstCapacityMismatch = firstStable(transformCompatible.stream()
                .filter(candidate -> !characterCapacityAllows(block.content(), candidate.slot())
                        || !itemCapacityAllows(candidate, slotOccupancy)).toList(),
                compositionComparator(slide, block.content(), slotOccupancy));
        if (firstCapacityMismatch != null) {
            capacityConstraint = characterCapacityAllows(block.content(), firstCapacityMismatch.slot())
                    ? "maxItems" : "maxCharacters";
        }
        Candidate selected = firstStable(feasible,
                compositionComparator(slide, block.content(), slotOccupancy));
        if (selected != null) {
            return SearchResult.success(selected);
        }
        return failedSearch(firstRoleMatch, firstTransformMismatch, firstCapacityMismatch, capacityConstraint);
    }

    private SearchResult findAssetCandidate(
            ContractModels.LockedPptSlide slide,
            ContractModels.LockedPptAssetReference asset,
            ContractTypes.ContentType contentType,
            ContractModels.ConfirmedTemplateProfile profile,
            Map<String, Integer> slotOccupancy,
            Set<String> nonReusableComponents,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy) {
        Candidate firstRoleMatch = null;
        Candidate firstTransformMismatch = null;
        Candidate firstCapacityMismatch = null;

        List<Candidate> roleMatches = roleCandidates(profile, asset.placementIntent().name(), null, null,
                nonReusableComponents, nativeReferenceOccupancy, nonReusableNativeReferenceOccupancy, false);
        firstRoleMatch = firstStable(roleMatches,
                compositionComparator(slide, null, slotOccupancy));
        List<Candidate> typeCompatible = roleMatches.stream()
                .filter(candidate -> acceptsContentType(candidate.slot(), contentType)).toList();
        List<Candidate> transformCompatible = typeCompatible.stream()
                .filter(candidate -> transformAllowed(slide.semanticLayout(), candidate.component())).toList();
        List<Candidate> feasible = transformCompatible.stream()
                .filter(candidate -> itemCapacityAllows(candidate, slotOccupancy)).toList();
        firstTransformMismatch = firstStable(typeCompatible.stream()
                .filter(candidate -> !transformAllowed(slide.semanticLayout(), candidate.component())).toList(),
                compositionComparator(slide, null, slotOccupancy));
        firstCapacityMismatch = firstStable(transformCompatible.stream()
                .filter(candidate -> !itemCapacityAllows(candidate, slotOccupancy)).toList(),
                compositionComparator(slide, null, slotOccupancy));
        Candidate selected = firstStable(feasible, compositionComparator(slide, null, slotOccupancy));
        if (selected != null) {
            return SearchResult.success(selected);
        }
        return failedSearch(firstRoleMatch, firstTransformMismatch, firstCapacityMismatch, "maxItems");
    }

    private SearchResult failedSearch(
            Candidate firstRoleMatch,
            Candidate firstTransformMismatch,
            Candidate firstCapacityMismatch,
            String capacityConstraint) {
        if (firstCapacityMismatch != null) {
            return SearchResult.failure(SLOT_CAPACITY_EXCEEDED, firstCapacityMismatch, capacityConstraint);
        }
        if (firstTransformMismatch != null) {
            return SearchResult.failure(TRANSFORM_NOT_ALLOWED, firstTransformMismatch, null);
        }
        if (firstRoleMatch != null) {
            return SearchResult.failure(SLOT_INCOMPATIBLE, firstRoleMatch, null);
        }
        return SearchResult.failure(COMPONENT_MISSING, null, null);
    }

    private boolean isExecutionEligible(ContractModels.TemplateComponent component) {
        return StableReferenceSetGate.isExecutionEligible(component);
    }

    private boolean transformAllowed(
            ContractModels.SemanticLayout layout,
            ContractModels.TemplateComponent component) {
        return layout.requestedTransform() == null
                || component.transformConstraint() == layout.requestedTransform()
                || component.transformConstraint() == RESPONSIVE;
    }

    private boolean characterCapacityAllows(String content, ContractModels.ComponentSlot slot) {
        Integer maxCharacters = slot.capacityConstraint() == null
                ? null : slot.capacityConstraint().maxCharacters();
        return maxCharacters == null || content.length() <= maxCharacters;
    }

    private boolean itemCapacityAllows(
            Candidate candidate,
            Map<String, Integer> slotOccupancy) {
        ContractModels.ComponentSlot slot = candidate.slot();
        Integer maxItems = slot.capacityConstraint() == null
                ? null : slot.capacityConstraint().maxItems();
        return maxItems == null || slotOccupancy.getOrDefault(slotKey(candidate), 0) < maxItems;
    }

    private void bind(
            Candidate candidate,
            Map<String, ContractModels.TemplateComponent> selectedComponents,
            Map<String, Integer> slotOccupancy,
            Set<String> nonReusableComponents,
            Map<String, Integer> nativeReferenceOccupancy,
            Map<String, Integer> nonReusableNativeReferenceOccupancy,
            ContractModels.ConfirmedTemplateProfile profile,
            String pageReferenceId) {
        selectedComponents.putIfAbsent(candidate.component().componentId(), candidate.component());
        slotOccupancy.merge(slotKey(candidate), 1, Integer::sum);
        ContractModels.TemplatePageReference page = page(profile, pageReferenceId);
        StableReferenceSetGate.Check referenceCheck = StableReferenceSetGate.inspect(
                candidate.component(), page, nativeReferenceOccupancy,
                nonReusableNativeReferenceOccupancy, nonReusableComponents);
        referenceCheck.referenceKeys().forEach(key -> nativeReferenceOccupancy.merge(key, 1, Integer::sum));
        if (!candidate.component().reusable()) {
            referenceCheck.referenceKeys().forEach(key ->
                    nonReusableNativeReferenceOccupancy.merge(key, 1, Integer::sum));
        }
        if (!candidate.component().reusable()) {
            nonReusableComponents.add(candidate.component().componentId());
        }
    }

    private String slotKey(Candidate candidate) {
        return candidate.component().componentId() + "::" + candidate.slot().slotId();
    }

    private void addRequiredSlotDiagnostics(
            ContractModels.LockedPptSlide slide,
            Map<String, ContractModels.TemplateComponent> selectedComponents,
            Map<String, Integer> slotOccupancy,
            List<ContractModels.Diagnostic> diagnostics) {
        selectedComponents.values().stream()
                .sorted(Comparator.comparing(ContractModels.TemplateComponent::componentId))
                .forEach(component -> {
                    for (ContractModels.ComponentSlot slot : component.slots()) {
                        Candidate candidate = new Candidate(component, slot);
                        if (slot.required() && slotOccupancy.getOrDefault(slotKey(candidate), 0) == 0) {
                            diagnostics.add(DiagnosticFactory.resolverError(
                                    REQUIRED_SLOT_UNFILLED, "resolver.requiredSlotUnfilled",
                                    slide.slideId(), slide.pageNumber(), null, null,
                                    component.componentId(), slot.slotId(), Map.of("required", "true")));
                        }
                    }
                });
    }

    private ContractTypes.ContentType contentType(ContractTypes.AssetType assetType) {
        return switch (assetType) {
            case IMAGE -> ContractTypes.ContentType.IMAGE;
            case CHART -> ContractTypes.ContentType.CHART;
            case TABLE -> ContractTypes.ContentType.TABLE;
            case ICON, VIDEO, OTHER -> null;
        };
    }

    private ContractModels.Diagnostic blockDiagnostic(
            ContractModels.LockedPptSlide slide,
            ContractModels.LockedPptContentBlock block,
            SearchResult search) {
        Candidate failedCandidate = search.failedCandidate();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("contentType", block.type().name());
        if (search.capacityConstraint() != null) {
            details.put("constraint", search.capacityConstraint());
        }
        return DiagnosticFactory.resolverError(
                search.failureCode(), messageKey(search.failureCode(), false),
                slide.slideId(), slide.pageNumber(), block.blockId(), null,
                componentId(failedCandidate), slotId(failedCandidate), details);
    }

    private ContractModels.Diagnostic assetDiagnostic(
            ContractModels.LockedPptSlide slide,
            ContractModels.LockedPptAssetReference asset,
            SearchResult search) {
        Candidate failedCandidate = search.failedCandidate();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("assetType", asset.assetType().name());
        if (search.capacityConstraint() != null) {
            details.put("constraint", search.capacityConstraint());
        }
        return DiagnosticFactory.resolverError(
                search.failureCode(), messageKey(search.failureCode(), true),
                slide.slideId(), slide.pageNumber(), null, asset.assetId(),
                componentId(failedCandidate), slotId(failedCandidate), details);
    }

    private String messageKey(ContractTypes.DiagnosticCode code, boolean asset) {
        return switch (code) {
            case COMPONENT_MISSING -> "resolver.componentMissing";
            case TRANSFORM_NOT_ALLOWED -> "resolver.transformNotAllowed";
            case SLOT_CAPACITY_EXCEEDED -> "resolver.slotCapacityExceeded";
            case CONTENT_OVERFLOW -> "resolver.contentOverflow";
            default -> asset ? "resolver.assetSlotIncompatible" : "resolver.slotIncompatible";
        };
    }

    private String componentId(Candidate candidate) {
        return candidate == null ? null : candidate.component().componentId();
    }

    private String slotId(Candidate candidate) {
        return candidate == null ? null : candidate.slot().slotId();
    }

    private record Candidate(
            ContractModels.TemplateComponent component,
            ContractModels.ComponentSlot slot) {
    }

    private record SearchResult(
            Candidate candidate,
            ContractTypes.DiagnosticCode failureCode,
            Candidate failedCandidate,
            String capacityConstraint) {

        private static SearchResult success(Candidate candidate) {
            return new SearchResult(candidate, null, null, null);
        }

        private static SearchResult failure(
                ContractTypes.DiagnosticCode code,
                Candidate failedCandidate,
                String capacityConstraint) {
            return new SearchResult(null, code, failedCandidate, capacityConstraint);
        }
    }

    public record ResolverResult(
            ContractModels.ResolvedSlidePlan plan,
            List<ContractModels.Diagnostic> diagnostics,
            List<CompositionModels.ComponentSelection> selections) {
        public ResolverResult(ContractModels.ResolvedSlidePlan plan,
                              List<ContractModels.Diagnostic> diagnostics) {
            this(plan, diagnostics, List.of());
        }

        public ResolverResult {
            diagnostics = List.copyOf(diagnostics);
            selections = List.copyOf(selections);
        }
    }

    private boolean acceptsContentType(
            ContractModels.ComponentSlot slot,
            ContractTypes.ContentType type) {
        return slot.acceptedContentTypes().contains(type)
                || (isTextType(type)
                && slot.acceptedContentTypes().contains(ContractTypes.ContentType.TEXT));
    }

    private boolean slotMatchesBlockType(
            ContractModels.ComponentSlot slot,
            ContractTypes.ContentType type) {
        return slot.semanticRole().equals(type.name())
                || (isTextType(type) && slot.semanticRole().equals(ContractTypes.ContentType.TEXT.name()));
    }

    private boolean isTextType(ContractTypes.ContentType type) {
        return type == ContractTypes.ContentType.TITLE
                || type == ContractTypes.ContentType.BODY
                || type == ContractTypes.ContentType.BULLETS
                || type == ContractTypes.ContentType.QUOTE;
    }
}
