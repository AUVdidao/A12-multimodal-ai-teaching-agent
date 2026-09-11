package com.auvdidao.a12teachingagent.agent.model;

public record ModelUsage(long promptTokens, long completionTokens, long totalTokens) {
    public ModelUsage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0 || totalTokens > 100_000_000L) {
            throw new IllegalArgumentException("usage is invalid");
        }
    }

    public static ModelUsage empty() {
        return new ModelUsage(0, 0, 0);
    }
}


