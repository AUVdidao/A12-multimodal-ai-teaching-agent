package com.auvdidao.a12teachingagent.lessonforge.material;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeVectorStore;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.ChunkEmbeddingRequest;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.EmbeddingRequest;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.EmbeddingResponse;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LessonForgeEmbeddingService {

    private final LessonForgeMaterialPipelineService pipelineService;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeVectorStore vectorStore;

    public LessonForgeEmbeddingService(
            LessonForgeMaterialPipelineService pipelineService,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeVectorStore vectorStore
    ) {
        this.pipelineService = pipelineService;
        this.chunkRepository = chunkRepository;
        this.vectorStore = vectorStore;
    }

    @Transactional
    public EmbeddingResponse persist(Long projectId, Long materialId, EmbeddingRequest request) {
        if (!vectorStore.available()) {
            throw new ConflictException("VECTOR_STORE_UNAVAILABLE");
        }
        pipelineService.requireBoundMaterial(projectId, materialId, request.identity());
        List<KnowledgeChunk> chunks = chunkRepository.findByMaterialIdOrderByChunkNoAsc(materialId).stream()
                .filter(chunk -> projectId.equals(chunk.getProjectId()))
                .toList();
        if (chunks.isEmpty()) {
            throw new ConflictException("KNOWLEDGE_CHUNKS_NOT_FOUND");
        }
        Map<Long, KnowledgeChunk> byId = new HashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            byId.put(chunk.getId(), chunk);
        }
        Set<Long> seen = new HashSet<>();
        List<KnowledgeVectorStore.VectorInput> inputs = request.embeddings().stream()
                .map(item -> toVectorInput(item, byId, request.dimension(), seen))
                .toList();
        if (inputs.size() != chunks.size() || seen.size() != chunks.size()) {
            throw new ConflictException("EMBEDDING_CHUNK_COUNT_MISMATCH");
        }
        KnowledgeVectorStore.PersistenceResult result;
        try {
            result = vectorStore.replace(projectId, materialId, request.modelConnectionId(), request.modelId(), request.dimension(), inputs);
        } catch (IllegalArgumentException exception) {
            throw new ConflictException("EMBEDDING_VECTOR_INVALID");
        }
        return new EmbeddingResponse(result.persistedCount(), result.dimension(), request.modelId().strip(), "READY");
    }

    private static KnowledgeVectorStore.VectorInput toVectorInput(
            ChunkEmbeddingRequest item,
            Map<Long, KnowledgeChunk> chunks,
            int dimension,
            Set<Long> seen
    ) {
        if (!chunks.containsKey(item.chunkId()) || item.vector().size() != dimension || !seen.add(item.chunkId())
                || item.vector().stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new ConflictException("EMBEDDING_VECTOR_INVALID");
        }
        return new KnowledgeVectorStore.VectorInput(item.chunkId(), item.vector());
    }
}
