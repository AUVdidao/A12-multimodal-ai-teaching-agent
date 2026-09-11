package com.auvdidao.a12teachingagent.ai.connection;

/** Short-lived execution binding. The decrypted key must never cross an API, trace, or log boundary. */
public record ResolvedModelConnection(
        Long id,
        Long ownerUserId,
        ModelConnectionProtocol protocol,
        String baseUrl,
        String modelId,
        String apiKey
) {
    public ResolvedModelConnection {
        if (id == null || ownerUserId == null || protocol == null || baseUrl == null || baseUrl.isBlank()
                || modelId == null || modelId.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("resolved model connection is incomplete");
        }
    }
}
