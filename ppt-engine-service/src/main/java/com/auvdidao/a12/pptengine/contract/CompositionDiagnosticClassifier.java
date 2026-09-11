package com.auvdidao.a12.pptengine.contract;

import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticImpact.ARTIFACT_INCOMPLETE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticImpact.JOB_BLOCKING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticImpact.NON_BLOCKING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.ERROR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.WARNING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.ASSET_MANIFEST_GATE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.CONTRACT_GATE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.LAYOUT_RESOLVER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.PLAN_VALIDATOR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.TEMPLATE_PAGE_RESOLVER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.GenerationJobStatus.FAILED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.GenerationJobStatus.PARTIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.GenerationJobStatus.SUCCEEDED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.GenerationJobStatus.SUCCEEDED_WITH_FEEDBACK;

/** Maps internal stage diagnostics to the frozen compose execution semantics. */
public final class CompositionDiagnosticClassifier {

    private CompositionDiagnosticClassifier() {
    }

    public static List<CompositionModels.CompositionDiagnostic> classify(
            List<ContractModels.Diagnostic> diagnostics) {
        return diagnostics.stream().map(CompositionDiagnosticClassifier::classify).toList();
    }

    public static ContractTypes.GenerationJobStatus status(
            List<CompositionModels.CompositionDiagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(item -> item.impact() == JOB_BLOCKING)) {
            return FAILED;
        }
        if (diagnostics.stream().anyMatch(item -> item.impact() == ARTIFACT_INCOMPLETE)) {
            return PARTIAL;
        }
        return diagnostics.isEmpty() ? SUCCEEDED : SUCCEEDED_WITH_FEEDBACK;
    }

    private static CompositionModels.CompositionDiagnostic classify(
            ContractModels.Diagnostic diagnostic) {
        return new CompositionModels.CompositionDiagnostic(
                diagnostic.code(), diagnostic.severity(), diagnostic.source(), impact(diagnostic),
                diagnostic.retryable(), diagnostic.slideId(), diagnostic.pageNumber(),
                diagnostic.blockId(), diagnostic.assetId(), diagnostic.componentId(),
                diagnostic.slotId(), diagnostic.messageKey(), diagnostic.safeDetails());
    }

    private static ContractTypes.DiagnosticImpact impact(ContractModels.Diagnostic diagnostic) {
        if (diagnostic.source() == CONTRACT_GATE || diagnostic.source() == PLAN_VALIDATOR) {
            return JOB_BLOCKING;
        }
        if (diagnostic.source() == TEMPLATE_PAGE_RESOLVER
                || ContractTypes.DiagnosticCode.TEMPLATE_PAGE_MISSING.name().equals(diagnostic.code())) {
            return JOB_BLOCKING;
        }
        if (diagnostic.source() == LAYOUT_RESOLVER && diagnostic.severity() == ERROR) {
            return JOB_BLOCKING;
        }
        if (diagnostic.source() == ASSET_MANIFEST_GATE) {
            return ContractTypes.DiagnosticCode.ASSET_APPROVED_OMISSION.name().equals(diagnostic.code())
                    ? NON_BLOCKING : JOB_BLOCKING;
        }
        return diagnostic.severity() == WARNING ? NON_BLOCKING : ARTIFACT_INCOMPLETE;
    }
}
