package com.auvdidao.a12teachingagent.embedding;

import java.util.List;
import java.util.Objects;

public record EmbeddingBatchResult(
        String provider,
        String model,
        int dimensions,
        List<EmbeddingVector> vectors
) {

    public EmbeddingBatchResult {
        provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(model, "model");
        vectors = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
    }
}
