package com.auvdidao.a12teachingagent.agent.model;

import java.util.List;
import java.util.Map;

public record ModelRequest(
        String systemInstruction,
        List<ModelMessage> messages,
        String modelHint,
        int maxCompletionTokens,
        double temperature,
        long timeoutMs,
        Map<String, String> metadata,
        List<String> tags
) {
    public static final int MAX_MESSAGES = 100;
    public static final int MAX_SYSTEM_CHARS = 60_000;
    public static final int MAX_MODEL_HINT_CHARS = 128;
    public static final int MAX_TAGS = 20;
    public static final int MAX_TAG_CHARS = 64;
    public static final int MAX_COMPLETION_TOKENS = 64_000;
    public static final long MAX_TIMEOUT_MS = 86_400_000L;

    public ModelRequest {
        if (systemInstruction != null && systemInstruction.length() > MAX_SYSTEM_CHARS) {
            throw new IllegalArgumentException("systemInstruction is too long");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("messages must contain between 1 and " + MAX_MESSAGES + " items");
        }
        if (modelHint != null && (modelHint.isBlank() || modelHint.length() > MAX_MODEL_HINT_CHARS)) {
            throw new IllegalArgumentException("modelHint is invalid");
        }
        if (maxCompletionTokens < 1 || maxCompletionTokens > MAX_COMPLETION_TOKENS) {
            throw new IllegalArgumentException("maxCompletionTokens is out of bounds");
        }
        if (Double.isNaN(temperature) || Double.isInfinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("timeoutMs is out of bounds");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (metadata.size() > ModelExecutionContext.MAX_METADATA) {
            throw new IllegalArgumentException("metadata is too large");
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 64
                    || entry.getValue() == null || entry.getValue().isBlank() || entry.getValue().length() > 256) {
                throw new IllegalArgumentException("metadata contains an invalid entry");
            }
            if (entry.getKey().matches("(?i).*?(api[_-]?key|authorization|bearer|credential|password|secret|token).*")) {
                throw new IllegalArgumentException("metadata must not contain secret-like keys");
            }
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (tags.size() > MAX_TAGS || tags.stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > MAX_TAG_CHARS)) {
            throw new IllegalArgumentException("tags are invalid");
        }
    }

    public ModelRequest(String systemInstruction, List<ModelMessage> messages, String modelHint,
                        int maxCompletionTokens, double temperature, long timeoutMs) {
        this(systemInstruction, messages, modelHint, maxCompletionTokens, temperature, timeoutMs, Map.of(), List.of());
    }

    public ModelRequest withAdditionalSystemInstruction(String addition) {
        String combined = systemInstruction == null || systemInstruction.isBlank()
                ? addition
                : systemInstruction + "\n" + addition;
        return new ModelRequest(combined, messages, modelHint, maxCompletionTokens, temperature, timeoutMs, metadata, tags);
    }
}


