package com.auvdidao.a12.pptengine.web;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.executor.ExecutorModels;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.EXECUTOR_CONTRACT_V1;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.GenerationJobStatus.FAILED;

/** Builds the same versioned response envelope for execute-side 4xx/5xx failures. */
public final class ExecuteErrorResponseFactory {

    private static final String EXECUTE_V1_PATH = "/internal/v1/execute";
    private static final String EXECUTE_V2_PATH = "/internal/v2/execute";

    private ExecuteErrorResponseFactory() {
    }

    public static boolean isExecutePath(String requestUri) {
        return EXECUTE_V1_PATH.equals(requestUri) || EXECUTE_V2_PATH.equals(requestUri);
    }

    public static boolean isExecuteV2Path(String requestUri) {
        return EXECUTE_V2_PATH.equals(requestUri);
    }

    public static ExecutorModels.ExecuteResponse fromDiagnostic(
            String requestId, ContractModels.Diagnostic diagnostic) {
        return fromDiagnostic(requestId, diagnostic, EXECUTOR_CONTRACT_V1);
    }

    public static ExecutorModels.ExecuteResponse fromDiagnostic(
            String requestId, ContractModels.Diagnostic diagnostic, String contractVersion) {
        String safeRequestId = safeRequestId(requestId);
        ExecutorModels.ExecutorDiagnostic feedback = new ExecutorModels.ExecutorDiagnostic(
                safeCode(diagnostic),
                diagnostic == null || diagnostic.severity() == null
                        ? ContractTypes.DiagnosticSeverity.ERROR : diagnostic.severity(),
                ContractTypes.DiagnosticImpact.JOB_BLOCKING,
                null,
                diagnostic == null ? null : diagnostic.slideId(),
                diagnostic == null ? null : diagnostic.pageNumber(),
                diagnostic == null ? null : diagnostic.assetId(),
                diagnostic == null ? null : diagnostic.componentId(),
                diagnostic == null ? null : diagnostic.slotId(),
                diagnostic == null ? "contract.invalid" : diagnostic.messageKey(),
                diagnostic == null ? java.util.Map.of() : diagnostic.safeDetails());
        return new ExecutorModels.ExecuteResponse(
                contractVersion, safeRequestId, null, null, FAILED,
                null, null, null, List.of(), List.of(feedback), null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static String safeCode(ContractModels.Diagnostic diagnostic) {
        return diagnostic == null || diagnostic.code() == null ? "CONTRACT_INVALID" : diagnostic.code();
    }

    private static String safeRequestId(String requestId) {
        return requestId != null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                ? requestId : "unknown";
    }
}
