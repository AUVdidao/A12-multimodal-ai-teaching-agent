package com.auvdidao.a12teachingagent.embedding;

import java.util.List;
import java.util.Objects;

public record EmbeddedQuery(
        String provider,
        String model,
        int dimensions,
        List<Double> vector
) {

    public EmbeddedQuery {
        provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(model, "model");
        vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
    }
}
