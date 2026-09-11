package com.auvdidao.a12.pptengine.resolver;

import com.auvdidao.a12.pptengine.contract.ContractModels;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single production predicate for executable stable-native references.
 *
 * The page resolver and component resolver must use this gate before a
 * candidate can enter either feasibility or ranking.  The contract model
 * currently carries slide/object identity (rather than a separate OPC part
 * field), so the canonical key includes the page scope, source slide,
 * object type and native object id.
 */
public final class StableReferenceSetGate {

    private StableReferenceSetGate() {
    }

    public static Check inspect(
            ContractModels.TemplateComponent component,
            ContractModels.TemplatePageReference page,
            Map<String, Integer> occupiedReferenceCounts,
            Map<String, Integer> occupiedNonReusableReferenceCounts,
            Set<String> occupiedNonReusableComponents) {
        List<String> reasons = new ArrayList<>();
        Set<String> referenceKeys = new HashSet<>();
        Set<String> slotKeys = new HashSet<>();

        if (component == null || component.componentId() == null
                || component.componentId().isBlank()) {
            reasons.add("COMPONENT_REFERENCE_INVALID");
            return new Check(false, reasons, referenceKeys, slotKeys);
        }
        if (!isExecutionEligible(component)) {
            reasons.add("PRESERVE_ONLY_OR_NOT_EXECUTION_READY");
        }
        if (page != null && component.sourceSlide() != page.sourceSlide()) {
            reasons.add("SOURCE_SLIDE_SCOPE_MISMATCH");
        }
        if (!component.reusable()
                && occupiedNonReusableComponents.contains(component.componentId())) {
            reasons.add("NON_REUSABLE_COMPONENT_REUSED");
        }
        if (component.shapeRefs() == null || component.shapeRefs().isEmpty()) {
            reasons.add("STABLE_REFERENCE_SET_EMPTY");
        } else {
            for (ContractModels.StableObjectReference reference : component.shapeRefs()) {
                if (reference == null || reference.objectType() == null
                        || reference.objectId() == null || reference.objectId().isBlank()) {
                    reasons.add("STABLE_REFERENCE_INVALID");
                    continue;
                }
                if (page != null && (page.objectIds() == null || page.objectIds().isEmpty()
                        || !page.objectIds().contains(reference.objectId()))) {
                    reasons.add("STABLE_REFERENCE_OUT_OF_PAGE_SCOPE");
                }
                String key = canonicalKey(component, page, reference);
                if (!referenceKeys.add(key)) {
                    reasons.add("DUPLICATE_STABLE_NATIVE_REFERENCE");
                }
                if (occupiedReferenceCounts.getOrDefault(key, 0) > 0
                        && (occupiedNonReusableReferenceCounts.getOrDefault(key, 0) > 0
                        || !component.reusable())) {
                    reasons.add("STABLE_NATIVE_REFERENCE_REUSE_NOT_ALLOWED");
                }
            }
        }

        if (component.slots() == null || component.slots().isEmpty()) {
            reasons.add("SLOT_SET_EMPTY");
        } else {
            for (ContractModels.ComponentSlot slot : component.slots()) {
                if (slot == null || slot.slotId() == null || slot.slotId().isBlank()) {
                    reasons.add("SLOT_REFERENCE_INVALID");
                } else if (!slotKeys.add(component.componentId() + "::" + slot.slotId())) {
                    reasons.add("DUPLICATE_SLOT_REFERENCE");
                }
            }
        }
        return new Check(reasons.isEmpty(), reasons, referenceKeys, slotKeys);
    }

    public static String canonicalKey(
            ContractModels.TemplateComponent component,
            ContractModels.TemplatePageReference page,
            ContractModels.StableObjectReference reference) {
        String pageScope = page == null ? "*" : page.pageReferenceId();
        return "slide=" + component.sourceSlide()
                + "|page=" + pageScope
                + "|object=" + reference.objectType().name() + "::" + reference.objectId();
    }

    public static boolean isExecutionEligible(ContractModels.TemplateComponent component) {
        return component != null && ("EXECUTION_READY".equals(component.executionEligibility())
                || (component.executionEligibility() == null
                && component.teacherConfirmed() && component.reusable()));
    }

    public record Check(
            boolean feasible,
            List<String> rejectionReasons,
            Set<String> referenceKeys,
            Set<String> slotKeys) {
        public Check {
            rejectionReasons = List.copyOf(rejectionReasons);
            referenceKeys = Set.copyOf(referenceKeys);
            slotKeys = Set.copyOf(slotKeys);
        }
    }
}
