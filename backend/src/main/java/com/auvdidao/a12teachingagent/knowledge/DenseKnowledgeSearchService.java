package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkEmbeddingRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DenseKnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(DenseKnowledgeSearchService.class);

    private final KnowledgeChunkEmbeddingRepository embeddingRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final VectorCodec vectorCodec;

    public DenseKnowledgeSearchService(
            KnowledgeChunkEmbeddingRepository embeddingRepository,
            KnowledgeChunkRepository chunkRepository,
            VectorCodec vectorCodec
    ) {
        this.embeddingRepository = embeddingRepository;
        this.chunkRepository = chunkRepository;
        this.vectorCodec = vectorCodec;
    }

    @Transactional(readOnly = true)
    public List<DenseKnowledgeHit> search(Long projectId, List<Double> queryVector, Integer requestedLimit) {
        long validProjectId = requireId(projectId, "projectId");
        int limit = requestedLimit == null ? 10 : requestedLimit;
        if (limit < 1 || limit > 20) {
            throw new BadRequestException("limit must be between 1 and 20");
        }
        List<Double> validQuery = validateQueryVector(queryVector);
        double queryNorm = norm(validQuery);

        List<KnowledgeChunkEmbedding> embeddings = embeddingRepository
                .findAllByProjectIdOrderByKnowledgeChunkIdAsc(validProjectId);
        if (embeddings.isEmpty()) {
            return List.of();
        }

        Set<Long> chunkIds = embeddings.stream()
                .map(KnowledgeChunkEmbedding::getKnowledgeChunkId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, KnowledgeChunk> chunksById = new HashMap<>();
        for (KnowledgeChunk chunk : chunkRepository.findAllById(chunkIds)) {
            if (chunk != null && chunk.getId() != null) {
                chunksById.putIfAbsent(chunk.getId(), chunk);
            }
        }

        List<ScoredHit> scored = new java.util.ArrayList<>();
        for (KnowledgeChunkEmbedding embedding : embeddings) {
            KnowledgeChunk chunk = validChunk(embedding, validProjectId, chunksById);
            if (chunk == null) {
                continue;
            }

            List<Double> vector;
            try {
                vector = vectorCodec.decode(embedding.getVector());
            } catch (IllegalArgumentException exception) {
                skip(embedding, "invalid vector JSON");
                continue;
            }
            if (embedding.getDimensions() == null
                    || embedding.getDimensions() != validQuery.size()
                    || vector.size() != embedding.getDimensions()) {
                skip(embedding, "incompatible vector dimensions");
                continue;
            }

            double vectorNorm;
            try {
                vectorNorm = norm(vector);
            } catch (IllegalArgumentException exception) {
                skip(embedding, "invalid vector norm");
                continue;
            }
            if (vectorNorm == 0) {
                skip(embedding, "zero stored vector");
                continue;
            }

            double score = cosine(validQuery, queryNorm, vector, vectorNorm);
            if (Double.isFinite(score)) {
                scored.add(new ScoredHit(chunk, score));
            } else {
                skip(embedding, "non-finite cosine score");
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredHit::score).reversed()
                        .thenComparing(value -> value.chunk().getId()))
                .limit(limit)
                .map(value -> toHit(value.chunk(), value.score()))
                .toList();
    }

    private KnowledgeChunk validChunk(
            KnowledgeChunkEmbedding embedding,
            long projectId,
            Map<Long, KnowledgeChunk> chunksById
    ) {
        Long chunkId = embedding.getKnowledgeChunkId();
        if (chunkId == null) {
            skip(embedding, "missing chunk id");
            return null;
        }
        KnowledgeChunk chunk = chunksById.get(chunkId);
        if (chunk == null) {
            skip(embedding, "chunk does not exist");
            return null;
        }
        if (!Long.valueOf(projectId).equals(embedding.getProjectId())
                || !Long.valueOf(projectId).equals(chunk.getProjectId())) {
            skip(embedding, "project mismatch");
            return null;
        }
        if (!java.util.Objects.equals(embedding.getMaterialId(), chunk.getMaterialId())) {
            skip(embedding, "material mismatch");
            return null;
        }
        if (chunk.getContent() == null
                || !KnowledgeEmbeddingStore.contentHash(chunk.getContent()).equals(embedding.getContentHash())) {
            skip(embedding, "stale content hash");
            return null;
        }
        return chunk;
    }

    private static DenseKnowledgeHit toHit(KnowledgeChunk chunk, double score) {
        return new DenseKnowledgeHit(
                chunk.getId(),
                chunk.getProjectId(),
                chunk.getMaterialId(),
                chunk.getChunkNo(),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getSourceFilename(),
                score
        );
    }

    private static List<Double> validateQueryVector(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new BadRequestException("queryVector must be non-empty");
        }
        for (Double value : vector) {
            if (value == null || !Double.isFinite(value)) {
                throw new BadRequestException("queryVector values must be finite numbers");
            }
        }
        if (norm(vector) == 0) {
            throw new BadRequestException("queryVector must not be a zero vector");
        }
        return List.copyOf(vector);
    }

    private static double norm(List<Double> vector) {
        double squared = 0;
        for (double value : vector) {
            squared += value * value;
        }
        double norm = Math.sqrt(squared);
        if (!Double.isFinite(norm)) {
            throw new IllegalArgumentException("Vector norm is not finite");
        }
        return norm;
    }

    private static double cosine(
            List<Double> query,
            double queryNorm,
            List<Double> vector,
            double vectorNorm
    ) {
        double dot = 0;
        for (int index = 0; index < query.size(); index++) {
            dot += query.get(index) * vector.get(index);
        }
        return dot / (queryNorm * vectorNorm);
    }

    private static long requireId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BadRequestException(field + " must be greater than 0");
        }
        return value;
    }

    private static void skip(KnowledgeChunkEmbedding embedding, String reason) {
        log.warn("Skipping dense embedding id={} chunkId={}: {}",
                embedding.getId(), embedding.getKnowledgeChunkId(), reason);
    }

    private record ScoredHit(KnowledgeChunk chunk, double score) {
    }
}
