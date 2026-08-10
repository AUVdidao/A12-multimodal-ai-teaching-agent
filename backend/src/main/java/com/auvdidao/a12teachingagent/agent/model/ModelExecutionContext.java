package com.auvdidao.a12teachingagent.agent.model;

import java.util.Map;

/** Non-secret identity and remaining budget supplied by a trusted server-side agent caller. */
public record ModelExecutionContext(
        long projectId,
        String actorId,
        String actorRole,
        String traceId,
        String runId,
        String agentName,
        long remainingBudgetMs,
        Map<String, String> metadata
) {
    public static final int MAX_ID_CHARS = 128;
    public static final long MAX_BUDGET_MS = 86_400_000L;
    public static final int MAX_METADATA = 20;
    public static final int MAX_METADATA_KEY_CHARS = 64;
    public static final int MAX_METADATA_VALUE_CHARS = 256;

    public ModelExecutionContext {
        if (projectId <= 0) {
            throw new IllegalArgumentException("projectId must be positive");
        }
        required(actorId, "actorId", MAX_ID_CHARS);
        required(actorRole, "actorRole", MAX_ID_CHARS);
        required(traceId, "traceId", MAX_ID_CHARS);
        required(runId, "runId", MAX_ID_CHARS);
        required(agentName, "agentName", MAX_ID_CHARS);
        if (remainingBudgetMs < 1 || remainingBudgetMs > MAX_BUDGET_MS) {
            throw new IllegalArgumentException("remainingBudgetMs is out of bounds");
        }
        metadata = boundedMetadata(metadata);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be nonblank and at most " + max + " characters");
        }
        return value;
    }

    private static Map<String, String> boundedMetadata(Map<String, String> values) {
        Map<String, String> copy = values == null ? Map.of() : Map.copyOf(values);
        if (copy.size() > MAX_METADATA) {
            throw new IllegalArgumentException("metadata must contain at most " + MAX_METADATA + " entries");
        }
        for (Map.Entry<String, String> entry : copy.entrySet()) {
            String key = required(entry.getKey(), "metadata key", MAX_METADATA_KEY_CHARS);
            String value = required(entry.getValue(), "metadata value", MAX_METADATA_VALUE_CHARS);
            if (key.matches("(?i).*?(api[_-]?key|authorization|bearer|credential|password|secret|token).*")) {
                throw new IllegalArgumentException("metadata must not contain secret-like keys");
            }
            if (value.matches("(?i).*?sk-[A-Za-z0-9_-]+.*")) {
                throw new IllegalArgumentException("metadata must not contain credential-like values");
            }
        }
        return copy;
    }
}
