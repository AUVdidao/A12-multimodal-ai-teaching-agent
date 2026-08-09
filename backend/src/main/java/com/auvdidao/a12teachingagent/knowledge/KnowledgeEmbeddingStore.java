package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkEmbeddingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class KnowledgeEmbeddingStore {

    private final KnowledgeChunkEmbeddingRepository embeddingRepository;
    private final VectorCodec vectorCodec;

    public KnowledgeEmbeddingStore(
            KnowledgeChunkEmbeddingRepository embeddingRepository,
            VectorCodec vectorCodec
    ) {
        this.embeddingRepository = embeddingRepository;
        this.vectorCodec = vectorCodec;
    }

    @Transactional
    public KnowledgeChunkEmbedding upsert(
            KnowledgeChunk chunk,
            String provider,
            String model,
            List<Double> vector
    ) {
        validateChunk(chunk);
        String validProvider = requireText(provider, "provider");
        String validModel = requireText(model, "model");
        String vectorJson = vectorCodec.encode(vector);

        KnowledgeChunkEmbedding embedding = embeddingRepository
                .findByKnowledgeChunkId(chunk.getId())
                .orElseGet(KnowledgeChunkEmbedding::new);
        embedding.setProjectId(chunk.getProjectId());
        embedding.setMaterialId(chunk.getMaterialId());
        embedding.setKnowledgeChunkId(chunk.getId());
        embedding.setProvider(validProvider);
        embedding.setModel(validModel);
        embedding.setDimensions(vector.size());
        embedding.setVector(vectorJson);
        embedding.setContentHash(contentHash(chunk.getContent()));
        return embeddingRepository.save(embedding);
    }

    @Transactional(readOnly = true)
    public Optional<KnowledgeChunkEmbedding> findByChunkId(Long knowledgeChunkId) {
        return embeddingRepository.findByKnowledgeChunkId(requireId(knowledgeChunkId, "knowledgeChunkId"));
    }

    @Transactional
    public long deleteByChunkId(Long knowledgeChunkId) {
        return embeddingRepository.deleteByKnowledgeChunkId(requireId(knowledgeChunkId, "knowledgeChunkId"));
    }

    @Transactional
    public long deleteByMaterialId(Long materialId) {
        return embeddingRepository.deleteByMaterialId(requireId(materialId, "materialId"));
    }

    @Transactional
    public long deleteByProjectId(Long projectId) {
        return embeddingRepository.deleteByProjectId(requireId(projectId, "projectId"));
    }

    @Transactional(readOnly = true)
    public long countByProjectId(Long projectId) {
        return embeddingRepository.countByProjectId(requireId(projectId, "projectId"));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeChunkEmbedding> findAllByProjectId(Long projectId) {
        return embeddingRepository.findAllByProjectIdOrderByKnowledgeChunkIdAsc(
                requireId(projectId, "projectId")
        );
    }

    static String contentHash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void validateChunk(KnowledgeChunk chunk) {
        if (chunk == null) {
            throw new BadRequestException("chunk is required");
        }
        requireId(chunk.getId(), "chunk.id");
        requireId(chunk.getProjectId(), "chunk.projectId");
        requireId(chunk.getMaterialId(), "chunk.materialId");
        if (chunk.getContent() == null || chunk.getContent().isBlank()) {
            throw new BadRequestException("chunk.content is required");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        return value.strip();
    }

    private static Long requireId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BadRequestException(field + " must be greater than 0");
        }
        return value;
    }
}
