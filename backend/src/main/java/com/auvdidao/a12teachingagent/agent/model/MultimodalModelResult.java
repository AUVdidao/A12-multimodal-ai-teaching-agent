package com.auvdidao.a12teachingagent.agent.model;

public record MultimodalModelResult(
        ModelProvider provider,
        String model,
        String requestId,
        String content,
        String finishReason,
        ModelUsage usage,
        long durationMs
) {
    public static final int MAX_CONTENT_CHARS = ModelResult.MAX_CONTENT_CHARS;
    public MultimodalModelResult {
        if (provider == null || model == null || model.isBlank() || content == null || content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("multimodal result is incomplete");
        }
        if (durationMs < 0 || durationMs > 86_400_000L) {
            throw new IllegalArgumentException("durationMs is invalid");
        }
        usage = usage == null ? ModelUsage.empty() : usage;
    }
}
