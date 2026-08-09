package com.auvdidao.a12teachingagent.agent.runtime;

import java.util.List;

/** Explicit success/failure result returned by future agent tools. */
public record ToolResult<T>(
        String toolCallId,
        String traceId,
        boolean success,
        T data,
        AgentFailureKind errorKind,
        boolean retryable,
        long durationMs,
        List<BusinessRef> sourceRefs,
        List<String> warnings
) {
    private static final int MAX_SOURCE_REFS = 50;
    private static final int MAX_WARNINGS = 20;
    private static final int MAX_WARNING_CHARS = 512;
    private static final long MAX_DURATION_MS = 86_400_000L;

    public ToolResult {
        toolCallId = ContractValidation.requiredText(toolCallId, "toolCallId", 128);
        traceId = ContractValidation.requiredText(traceId, "traceId", AgentContext.MAX_TRACE_ID_CHARS);
        ContractValidation.requireRange(durationMs, 0, MAX_DURATION_MS, "durationMs");
        sourceRefs = ContractValidation.immutableList(sourceRefs, "sourceRefs", MAX_SOURCE_REFS);
        warnings = ContractValidation.immutableTextList(
                warnings,
                "warnings",
                MAX_WARNINGS,
                MAX_WARNING_CHARS
        );
        if (success) {
            if (errorKind != null) {
                throw new IllegalArgumentException("successful result must not have errorKind");
            }
            if (retryable) {
                throw new IllegalArgumentException("successful result must not be retryable");
            }
        } else if (errorKind == null) {
            throw new IllegalArgumentException("failed result must have errorKind");
        }
    }

    public static <T> ToolResult<T> success(String toolCallId, String traceId, T data, long durationMs) {
        return success(toolCallId, traceId, data, durationMs, List.of(), List.of());
    }

    public static <T> ToolResult<T> success(
            String toolCallId,
            String traceId,
            T data,
            long durationMs,
            List<BusinessRef> sourceRefs,
            List<String> warnings
    ) {
        return new ToolResult<>(toolCallId, traceId, true, data, null, false, durationMs, sourceRefs, warnings);
    }

    public static <T> ToolResult<T> failure(
            String toolCallId,
            String traceId,
            AgentFailureKind errorKind,
            long durationMs
    ) {
        if (errorKind == null) {
            throw new IllegalArgumentException("errorKind must not be null");
        }
        return failure(toolCallId, traceId, errorKind, errorKind.retryableDefault(), durationMs, List.of(), List.of());
    }

    public static <T> ToolResult<T> failure(
            String toolCallId,
            String traceId,
            AgentFailureKind errorKind,
            boolean retryable,
            long durationMs,
            List<BusinessRef> sourceRefs,
            List<String> warnings
    ) {
        if (errorKind == null) {
            throw new IllegalArgumentException("errorKind must not be null");
        }
        return new ToolResult<>(toolCallId, traceId, false, null, errorKind, retryable, durationMs, sourceRefs, warnings);
    }
}
