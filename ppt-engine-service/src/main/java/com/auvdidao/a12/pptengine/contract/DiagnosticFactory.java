package com.auvdidao.a12.pptengine.contract;

import java.util.Map;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.ERROR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.WARNING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.COMPONENT_RESOLVER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.CONTRACT_GATE;

public final class DiagnosticFactory {

    private DiagnosticFactory() {
    }

    public static ContractModels.Diagnostic gateError(
            String code,
            String messageKey,
            String slideId,
            Integer pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            Map<String, String> details) {
        return new ContractModels.Diagnostic(
                code, ERROR, CONTRACT_GATE, false, slideId, pageNumber, blockId,
                assetId, componentId, slotId, messageKey, details);
    }

    public static ContractModels.Diagnostic gateError(
            ContractTypes.DiagnosticCode code,
            String messageKey,
            String slideId,
            Integer pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            Map<String, String> details) {
        return gateError(code.name(), messageKey, slideId, pageNumber, blockId, assetId,
                componentId, slotId, details);
    }

    public static ContractModels.Diagnostic resolverError(
            String code,
            String messageKey,
            String slideId,
            int pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            Map<String, String> details) {
        return new ContractModels.Diagnostic(
                code, ERROR, COMPONENT_RESOLVER, false, slideId, pageNumber, blockId,
                assetId, componentId, slotId, messageKey, details);
    }

    public static ContractModels.Diagnostic resolverError(
            ContractTypes.DiagnosticCode code,
            String messageKey,
            String slideId,
            int pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            Map<String, String> details) {
        return resolverError(code.name(), messageKey, slideId, pageNumber, blockId, assetId,
                componentId, slotId, details);
    }

    public static ContractModels.Diagnostic resolverWarning(
            String code,
            String messageKey,
            String slideId,
            int pageNumber,
            String assetId,
            Map<String, String> details) {
        return new ContractModels.Diagnostic(
                code, WARNING, COMPONENT_RESOLVER, false, slideId, pageNumber, null,
                assetId, null, null, messageKey, details);
    }

    public static ContractModels.Diagnostic resolverWarning(
            ContractTypes.DiagnosticCode code,
            String messageKey,
            String slideId,
            int pageNumber,
            String assetId,
            Map<String, String> details) {
        return resolverWarning(code.name(), messageKey, slideId, pageNumber, assetId, details);
    }

    public static ContractModels.Diagnostic stageError(
            ContractTypes.DiagnosticSource source,
            ContractTypes.DiagnosticCode code,
            String messageKey,
            String slideId,
            Integer pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            Map<String, String> details) {
        return new ContractModels.Diagnostic(
                code.name(), ERROR, source, false, slideId, pageNumber, blockId,
                assetId, componentId, slotId, messageKey, details);
    }

    public static ContractModels.Diagnostic stageWarning(
            ContractTypes.DiagnosticSource source,
            ContractTypes.DiagnosticCode code,
            String messageKey,
            String slideId,
            Integer pageNumber,
            String blockId,
            String assetId,
            String componentId,
            String slotId,
            Map<String, String> details) {
        return new ContractModels.Diagnostic(
                code.name(), WARNING, source, false, slideId, pageNumber, blockId,
                assetId, componentId, slotId, messageKey, details);
    }
}
