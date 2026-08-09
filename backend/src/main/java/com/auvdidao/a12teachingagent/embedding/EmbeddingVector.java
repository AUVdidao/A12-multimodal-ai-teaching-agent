package com.auvdidao.a12teachingagent.embedding;

import java.util.List;
import java.util.Objects;

public record EmbeddingVector(int index, List<Double> values) {

    public EmbeddingVector {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
    }
}
