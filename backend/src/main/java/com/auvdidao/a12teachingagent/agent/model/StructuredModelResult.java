package com.auvdidao.a12teachingagent.agent.model;

public record StructuredModelResult<T>(
        T value,
        ModelProvider provider,
        String model,
        String requestId,
        ModelUsage usage,
        long durationMs,
        boolean repairAttempted
) {
    public StructuredModelResult {
        if (value == null || provider == null || model == null || model.isBlank()) {
            throw new IllegalArgumentException("structured result is incomplete");
        }
        if (durationMs < 0 || durationMs > 86_400_000L) {
            throw new IllegalArgumentException("durationMs is invalid");
        }
        usage = usage == null ? ModelUsage.empty() : usage;
    }
}


