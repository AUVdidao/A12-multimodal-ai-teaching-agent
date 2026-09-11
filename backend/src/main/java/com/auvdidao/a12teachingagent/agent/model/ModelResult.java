package com.auvdidao.a12teachingagent.agent.model;

import java.util.List;

public record ModelResult(
        ModelProvider provider,
        String model,
        String requestId,
        String content,
        String finishReason,
        ModelUsage usage,
        long durationMs,
        List<String> warnings
) {
    public static final int MAX_CONTENT_CHARS = 1_000_000;
    public static final int MAX_WARNINGS = 20;
    public static final int MAX_WARNING_CHARS = 512;

    public ModelResult {
        if (provider == null || model == null || model.isBlank() || model.length() > 128) {
            throw new IllegalArgumentException("provider and model are required");
        }
        if (requestId != null && requestId.length() > 256) {
            throw new IllegalArgumentException("requestId is too long");
        }
        if (content == null || content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("content is invalid");
        }
        if (durationMs < 0 || durationMs > 86_400_000L) {
            throw new IllegalArgumentException("durationMs is invalid");
        }
        usage = usage == null ? ModelUsage.empty() : usage;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (warnings.size() > MAX_WARNINGS || warnings.stream()
                .anyMatch(warning -> warning == null || warning.isBlank() || warning.length() > MAX_WARNING_CHARS)) {
            throw new IllegalArgumentException("warnings are invalid");
        }
    }
}


