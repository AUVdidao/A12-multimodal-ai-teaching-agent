package com.auvdidao.a12teachingagent.knowledge;

public record DenseKnowledgeHit(
        Long chunkId,
        Long projectId,
        Long materialId,
        Integer chunkNo,
        String title,
        String content,
        String sourceFilename,
        double score
) {
}
