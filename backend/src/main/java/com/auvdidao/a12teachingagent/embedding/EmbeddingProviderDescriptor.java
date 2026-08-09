package com.auvdidao.a12teachingagent.embedding;

import java.util.Objects;

/**
 * Describes the locally configured target embedding space without making a
 * network request or exposing credentials.
 */
public record EmbeddingProviderDescriptor(
        String provider,
        String model,
        boolean enabled,
        boolean configured
) {

    public EmbeddingProviderDescriptor {
        provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(model, "model");
    }
}
