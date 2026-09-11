package com.auvdidao.a12.pptengine.web;

import com.auvdidao.a12.pptengine.contract.CompositionDiagnosticClassifier;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSE_CONTRACT_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSITION_FEEDBACK_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.GenerationJobStatus.FAILED;

public final class ComposeErrorResponseFactory {

    private static final String COMPOSE_PATH = "/internal/v1/compose-plan";

    private ComposeErrorResponseFactory() {
    }

    public static boolean isComposePath(String requestUri) {
        return COMPOSE_PATH.equals(requestUri);
    }

    public static CompositionModels.EngineComposePlanErrorResponse fromDiagnostic(
            String requestId, ContractModels.Diagnostic diagnostic) {
        String safeRequestId = safeRequestId(requestId);
        CompositionModels.CompositionFeedback feedback = new CompositionModels.CompositionFeedback(
                COMPOSE_CONTRACT_V2,
                COMPOSITION_FEEDBACK_V2,
                safeRequestId,
                null, null, null, null, null, null, null,
                FAILED,
                CompositionDiagnosticClassifier.classify(List.of(diagnostic)),
                OffsetDateTime.now(ZoneOffset.UTC));
        return new CompositionModels.EngineComposePlanErrorResponse(
                COMPOSE_CONTRACT_V2, COMPOSITION_FEEDBACK_V2, safeRequestId, feedback);
    }

    public static CompositionModels.EngineComposePlanErrorResponse fromFeedback(
            CompositionModels.CompositionFeedback feedback) {
        String safeRequestId = safeRequestId(feedback == null ? null : feedback.requestId());
        return new CompositionModels.EngineComposePlanErrorResponse(
                COMPOSE_CONTRACT_V2,
                COMPOSITION_FEEDBACK_V2,
                safeRequestId,
                feedback == null ? fromDiagnostic(safeRequestId,
                        new ContractModels.Diagnostic(
                                "CONTRACT_INVALID", ContractTypes.DiagnosticSeverity.ERROR,
                                ContractTypes.DiagnosticSource.CONTRACT_GATE, false,
                                null, null, null, null, null, null,
                                "contract.invalid", java.util.Map.of())).feedback() : feedback);
    }

    private static String safeRequestId(String requestId) {
        if (requestId != null && requestId.length() <= 64
                && requestId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return requestId;
        }
        return "unknown";
    }
}
