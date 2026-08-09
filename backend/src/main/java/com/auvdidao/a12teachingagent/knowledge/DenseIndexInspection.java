package com.auvdidao.a12teachingagent.knowledge;

public record DenseIndexInspection(
        Long projectId,
        Long materialId,
        String provider,
        String model,
        long chunkCount,
        long embeddingRowCount,
        long readyCount,
        long missingEmbeddingCount,
        long staleEmbeddingCount,
        long orphanEmbeddingCount,
        long wrongProjectCount,
        long wrongMaterialCount,
        long invalidVectorCount,
        long dimensionMismatchCount,
        Integer dimensions,
        boolean denseReady
) {
}
