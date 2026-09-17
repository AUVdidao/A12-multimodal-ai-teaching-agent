package com.auvdidao.a12teachingagent.knowledge;

import java.util.List;
import java.util.Map;

/**
 * Persistent vector boundary for LessonForge knowledge chunks. The production
 * PostgreSQL implementation stores vectors in a database array and computes
 * cosine similarity in SQL; it never uses an in-memory vector index.
 */
public interface KnowledgeVectorStore {

    record VectorInput(Long chunkId, List<Double> vector) {
    }

    record VectorHit(Long chunkId, Long materialId, double score) {
    }

    record PersistenceResult(int persistedCount, int dimension) {
    }

    PersistenceResult replace(
            Long projectId,
            Long materialId,
            Long modelConnectionId,
            String modelId,
            int dimension,
            List<VectorInput> vectors
    );

    List<VectorHit> search(
            Long projectId,
            List<Long> materialIds,
            List<Double> queryVector,
            int dimension,
            int limit
    );

    boolean hasCompleteIndex(Long projectId, List<Long> materialIds, int dimension);

    boolean available();
}
