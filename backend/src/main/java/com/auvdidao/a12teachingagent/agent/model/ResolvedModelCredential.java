package com.auvdidao.a12teachingagent.agent.model;

/** Short-lived provider adapter input. Do not return this from an agent, result, trace, or exception. */
public record ResolvedModelCredential(
        ModelProvider provider,
        CredentialSource source,
        String value
) {
    public ResolvedModelCredential {
        if (provider == null || source == null || value == null || value.isBlank()) {
            throw new IllegalArgumentException("resolved credential is incomplete");
        }
    }
}
