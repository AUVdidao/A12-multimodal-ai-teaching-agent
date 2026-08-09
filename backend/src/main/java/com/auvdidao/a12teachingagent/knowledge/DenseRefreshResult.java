package com.auvdidao.a12teachingagent.knowledge;

public record DenseRefreshResult(
        Long projectId,
        Long materialId,
        String provider,
        String model,
        long chunkCount,
        long embeddedCount,
        long reusedCount,
        long refreshedCount,
        Integer dimensions,
        boolean denseReady
) {
}
