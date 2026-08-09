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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DenseIndexIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(DenseIndexIntegrityService.class);

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeChunkEmbeddingRepository embeddingRepository;
    private final VectorCodec vectorCodec;

    public DenseIndexIntegrityService(
            KnowledgeChunkRepository chunkRepository,
            KnowledgeChunkEmbeddingRepository embeddingRepository,
            VectorCodec vectorCodec
    ) {
        this.chunkRepository = chunkRepository;
        this.embeddingRepository = embeddingRepository;
        this.vectorCodec = vectorCodec;
    }

    @Transactional(readOnly = true)
    public DenseIndexInspection inspectProject(Long projectId, String provider, String model) {
        long validProjectId = requireId(projectId, "projectId");
        String validProvider = requireIdentity(provider, "provider");
        String validModel = requireIdentity(model, "model");
        List<KnowledgeChunk> chunks = safeList(
                chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(validProjectId)
        );
        return inspect(validProjectId, null, validProvider, validModel, chunks, chunks);
    }

    @Transactional(readOnly = true)
    public DenseIndexInspection inspectMaterial(
            Long projectId,
            Long materialId,
            String provider,
            String model
    ) {
        long validProjectId = requireId(projectId, "projectId");
        long validMaterialId = requireId(materialId, "materialId");
        String validProvider = requireIdentity(provider, "provider");
        String validModel = requireIdentity(model, "model");
        List<KnowledgeChunk> materialChunks = safeList(
                chunkRepository.findByMaterialIdOrderByChunkNoAsc(validMaterialId)
        );
        List<KnowledgeChunk> scopedChunks = materialChunks.stream()
                .filter(chunk -> Objects.equals(chunk.getProjectId(), validProjectId))
                .toList();
        return inspect(
                validProjectId,
                validMaterialId,
                validProvider,
                validModel,
                scopedChunks,
                materialChunks
        );
    }

    @Transactional
    public long cleanupOrphansForProject(Long projectId, String provider, String model) {
        long validProjectId = requireId(projectId, "projectId");
        String validProvider = requireIdentity(provider, "provider");
        String validModel = requireIdentity(model, "model");
        List<KnowledgeChunkEmbedding> embeddings = safeList(
                embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                        validProjectId,
                        validProvider,
                        validModel
                )
        );
        long deleted = deleteOrphans(
                validProjectId,
                null,
                validProvider,
                validModel,
                embeddings
        );
        log.info(
                "Cleaned dense orphan embeddings projectId={} provider={} model={} deletedCount={}",
                validProjectId,
                validProvider,
                validModel,
                deleted
        );
        return deleted;
    }

    @Transactional
    public long cleanupOrphansForMaterial(
            Long projectId,
            Long materialId,
            String provider,
            String model
    ) {
        long validProjectId = requireId(projectId, "projectId");
        long validMaterialId = requireId(materialId, "materialId");
        String validProvider = requireIdentity(provider, "provider");
        String validModel = requireIdentity(model, "model");
        List<KnowledgeChunkEmbedding> embeddings = safeList(
                embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                        validProjectId,
                        validProvider,
                        validModel
                )
        );
        long deleted = deleteOrphans(
                validProjectId,
                validMaterialId,
                validProvider,
                validModel,
                embeddings
        );
        log.info(
                "Cleaned dense orphan embeddings projectId={} materialId={} provider={} model={} deletedCount={}",
                validProjectId,
                validMaterialId,
                validProvider,
                validModel,
                deleted
        );
        return deleted;
    }

    private DenseIndexInspection inspect(
            long projectId,
            Long materialId,
            String provider,
            String model,
            List<KnowledgeChunk> scopedChunks,
            List<KnowledgeChunk> knownChunks
    ) {
        List<KnowledgeChunkEmbedding> allEmbeddings = safeList(
                embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                        projectId,
                        provider,
                        model
                )
        );
        Map<Long, KnowledgeChunk> chunksById = loadChunksById(allEmbeddings, knownChunks);
        Set<Long> scopedChunkIds = new HashSet<>();
        for (KnowledgeChunk chunk : scopedChunks) {
            if (chunk != null && chunk.getId() != null) {
                scopedChunkIds.add(chunk.getId());
            }
        }

        List<KnowledgeChunkEmbedding> scopedEmbeddings = allEmbeddings.stream()
                .filter(embedding -> inMaterialScope(embedding, materialId, chunksById))
                .toList();
        Map<Long, KnowledgeChunkEmbedding> embeddingByChunkId = new HashMap<>();
        for (KnowledgeChunkEmbedding embedding : scopedEmbeddings) {
            if (embedding != null && embedding.getKnowledgeChunkId() != null) {
                embeddingByChunkId.putIfAbsent(embedding.getKnowledgeChunkId(), embedding);
            }
        }

        Counts counts = new Counts();
        List<Integer> readyDimensions = new ArrayList<>();
        Set<KnowledgeChunkEmbedding> processed = new HashSet<>();
        for (KnowledgeChunk chunk : scopedChunks) {
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            KnowledgeChunkEmbedding embedding = embeddingByChunkId.get(chunk.getId());
            if (embedding == null) {
                counts.missingEmbeddingCount++;
                continue;
            }
            processed.add(embedding);
            if (!Objects.equals(embedding.getProjectId(), chunk.getProjectId())) {
                counts.wrongProjectCount++;
                continue;
            }
            if (!Objects.equals(embedding.getMaterialId(), chunk.getMaterialId())) {
                counts.wrongMaterialCount++;
                continue;
            }
            if (!provider.equals(embedding.getProvider()) || !model.equals(embedding.getModel())) {
                counts.missingEmbeddingCount++;
                continue;
            }
            inspectEmbedding(embedding, chunk, counts, readyDimensions);
        }

        for (KnowledgeChunkEmbedding embedding : scopedEmbeddings) {
            if (processed.contains(embedding)) {
                continue;
            }
            KnowledgeChunk chunk = embedding == null
                    ? null
                    : chunksById.get(embedding.getKnowledgeChunkId());
            if (chunk == null) {
                counts.orphanEmbeddingCount++;
            } else if (!Objects.equals(embedding.getProjectId(), chunk.getProjectId())) {
                counts.wrongProjectCount++;
            } else if (!Objects.equals(embedding.getMaterialId(), chunk.getMaterialId())) {
                counts.wrongMaterialCount++;
            }
        }

        Integer dimensions = null;
        if (!readyDimensions.isEmpty()) {
            int first = readyDimensions.get(0);
            dimensions = readyDimensions.stream().allMatch(value -> value == first) ? first : null;
            counts.dimensionMismatchCount = readyDimensions.stream()
                    .filter(value -> value != first)
                    .count();
        }
        boolean denseReady = scopedChunks.size() > 0
                && counts.readyCount == scopedChunks.size()
                && counts.missingEmbeddingCount == 0
                && counts.staleEmbeddingCount == 0
                && counts.orphanEmbeddingCount == 0
                && counts.wrongProjectCount == 0
                && counts.wrongMaterialCount == 0
                && counts.invalidVectorCount == 0
                && counts.dimensionMismatchCount == 0
                && dimensions != null;
        return new DenseIndexInspection(
                projectId,
                materialId,
                provider,
                model,
                scopedChunks.size(),
                scopedEmbeddings.size(),
                counts.readyCount,
                counts.missingEmbeddingCount,
                counts.staleEmbeddingCount,
                counts.orphanEmbeddingCount,
                counts.wrongProjectCount,
                counts.wrongMaterialCount,
                counts.invalidVectorCount,
                counts.dimensionMismatchCount,
                dimensions,
                denseReady
        );
    }

    private void inspectEmbedding(
            KnowledgeChunkEmbedding embedding,
            KnowledgeChunk chunk,
            Counts counts,
            List<Integer> readyDimensions
    ) {
        if (embedding.getDimensions() == null || embedding.getDimensions() <= 0) {
            counts.invalidVectorCount++;
            return;
        }
        List<Double> vector;
        try {
            vector = vectorCodec.decode(embedding.getVector());
        } catch (IllegalArgumentException exception) {
            counts.invalidVectorCount++;
            return;
        }
        if (vector.size() != embedding.getDimensions()) {
            counts.invalidVectorCount++;
            return;
        }
        double norm;
        try {
            norm = norm(vector);
        } catch (IllegalArgumentException exception) {
            counts.invalidVectorCount++;
            return;
        }
        if (norm == 0) {
            counts.invalidVectorCount++;
            return;
        }
        String currentHash = chunk.getContent() == null
                ? null
                : KnowledgeEmbeddingStore.contentHash(chunk.getContent());
        if (!Objects.equals(currentHash, embedding.getContentHash())) {
            counts.staleEmbeddingCount++;
            return;
        }
        counts.readyCount++;
        readyDimensions.add(embedding.getDimensions());
    }

    private long deleteOrphans(
            long projectId,
            Long materialId,
            String provider,
            String model,
            List<KnowledgeChunkEmbedding> embeddings
    ) {
        Set<Long> chunkIds = embeddings.stream()
                .filter(Objects::nonNull)
                .map(KnowledgeChunkEmbedding::getKnowledgeChunkId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, KnowledgeChunk> chunksById = new HashMap<>();
        for (KnowledgeChunk chunk : safeList(chunkRepository.findAllById(chunkIds))) {
            if (chunk != null && chunk.getId() != null) {
                chunksById.putIfAbsent(chunk.getId(), chunk);
            }
        }
        List<KnowledgeChunkEmbedding> orphans = embeddings.stream()
                .filter(Objects::nonNull)
                .filter(embedding -> Objects.equals(embedding.getProjectId(), projectId))
                .filter(embedding -> provider.equals(embedding.getProvider()))
                .filter(embedding -> model.equals(embedding.getModel()))
                .filter(embedding -> materialId == null || Objects.equals(embedding.getMaterialId(), materialId))
                .filter(embedding -> embedding.getKnowledgeChunkId() == null
                        || !chunksById.containsKey(embedding.getKnowledgeChunkId()))
                .toList();
        if (!orphans.isEmpty()) {
            embeddingRepository.deleteAll(orphans);
        }
        return orphans.size();
    }

    private Map<Long, KnowledgeChunk> loadChunksById(
            List<KnowledgeChunkEmbedding> embeddings,
            List<KnowledgeChunk> knownChunks
    ) {
        Set<Long> chunkIds = new LinkedHashSet<>();
        for (KnowledgeChunkEmbedding embedding : embeddings) {
            if (embedding != null && embedding.getKnowledgeChunkId() != null) {
                chunkIds.add(embedding.getKnowledgeChunkId());
            }
        }
        Map<Long, KnowledgeChunk> chunksById = new HashMap<>();
        for (KnowledgeChunk chunk : knownChunks) {
            if (chunk != null && chunk.getId() != null) {
                chunksById.putIfAbsent(chunk.getId(), chunk);
            }
        }
        for (KnowledgeChunk chunk : safeList(chunkRepository.findAllById(chunkIds))) {
            if (chunk != null && chunk.getId() != null) {
                chunksById.putIfAbsent(chunk.getId(), chunk);
            }
        }
        return chunksById;
    }

    private static boolean inMaterialScope(
            KnowledgeChunkEmbedding embedding,
            Long materialId,
            Map<Long, KnowledgeChunk> chunksById
    ) {
        if (embedding == null) {
            return false;
        }
        if (materialId == null) {
            return true;
        }
        if (Objects.equals(embedding.getMaterialId(), materialId)) {
            return true;
        }
        KnowledgeChunk chunk = chunksById.get(embedding.getKnowledgeChunkId());
        return chunk != null && Objects.equals(chunk.getMaterialId(), materialId);
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

    private static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        return value.strip();
    }

    private static long requireId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BadRequestException(field + " must be greater than 0");
        }
        return value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static final class Counts {
        private long readyCount;
        private long missingEmbeddingCount;
        private long staleEmbeddingCount;
        private long orphanEmbeddingCount;
        private long wrongProjectCount;
        private long wrongMaterialCount;
        private long invalidVectorCount;
        private long dimensionMismatchCount;
    }
}
