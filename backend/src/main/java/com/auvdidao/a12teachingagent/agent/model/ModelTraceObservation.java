package com.auvdidao.a12teachingagent.agent.model;

public record ModelTraceObservation(
        ModelProvider provider,
        String model,
        String requestId,
        long durationMs,
        boolean success,
        ModelFailureKind failureKind
) {
    public ModelTraceObservation {
        if (provider == null || model == null || model.isBlank() || model.length() > 128) {
            throw new IllegalArgumentException("provider and model are required");
        }
        if (requestId != null && requestId.length() > 256) {
            throw new IllegalArgumentException("requestId is too long");
        }
        if (durationMs < 0 || durationMs > 86_400_000L) {
            throw new IllegalArgumentException("durationMs is invalid");
        }
        if (success && failureKind != null) {
            throw new IllegalArgumentException("successful model call must not have failureKind");
        }
        if (!success && failureKind == null) {
            throw new IllegalArgumentException("failed model call must have failureKind");
        }
    }

    public static ModelTraceObservation success(ModelProvider provider, String model, String requestId, long durationMs) {
        return new ModelTraceObservation(provider, model, requestId, durationMs, true, null);
    }

    public static ModelTraceObservation failure(ModelProvider provider, String model, String requestId,
                                                long durationMs, ModelFailureKind failureKind) {
        return new ModelTraceObservation(provider, model, requestId, durationMs, false, failureKind);
    }
}


