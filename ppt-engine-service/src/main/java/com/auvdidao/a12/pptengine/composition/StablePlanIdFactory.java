package com.auvdidao.a12.pptengine.composition;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.springframework.stereotype.Component;

@Component
public class StablePlanIdFactory {

    private final ChecksumService checksumService;

    public StablePlanIdFactory(ChecksumService checksumService) {
        this.checksumService = checksumService;
    }

    public String placementId(
            String slideId,
            ContractTypes.SlotBindingKind bindingKind,
            String bindingId,
            String componentId,
            String slotId) {
        return "placement-" + checksumService.sha256Parts(
                "placement", slideId, bindingKind.name(), bindingId, componentId, slotId);
    }

    public String operationId(
            ContractTypes.CompositionOperationType operationType,
            String slideId,
            String... stableReferences) {
        String[] parts = new String[stableReferences.length + 3];
        parts[0] = "operation";
        parts[1] = operationType.name();
        parts[2] = slideId;
        System.arraycopy(stableReferences, 0, parts, 3, stableReferences.length);
        return "operation-" + checksumService.sha256Parts(parts);
    }
}
