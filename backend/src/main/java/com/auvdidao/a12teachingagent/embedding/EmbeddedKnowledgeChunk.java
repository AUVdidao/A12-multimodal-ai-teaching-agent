package com.auvdidao.a12teachingagent.embedding;

import java.util.List;
import java.util.Objects;

public record EmbeddedKnowledgeChunk(
        Long chunkId,
        Long projectId,
        Long materialId,
        Integer chunkNo,
        String sourceFilename,
        String provider,
        String model,
        int dimensions,
        List<Double> vector
) {

    public EmbeddedKnowledgeChunk {
        chunkId = Objects.requireNonNull(chunkId, "chunkId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        materialId = Objects.requireNonNull(materialId, "materialId");
        chunkNo = Objects.requireNonNull(chunkNo, "chunkNo");
        provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(model, "model");
        vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
    }
}
