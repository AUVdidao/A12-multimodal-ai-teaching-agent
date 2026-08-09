package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.embedding.EmbeddedKnowledgeChunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Persists one complete refresh result in one short transaction. */
@Service
public class DenseEmbeddingPersistenceService {

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeEmbeddingStore embeddingStore;

    public DenseEmbeddingPersistenceService(
            KnowledgeChunkRepository chunkRepository,
            KnowledgeEmbeddingStore embeddingStore
    ) {
        this.chunkRepository = chunkRepository;
        this.embeddingStore = embeddingStore;
    }

    @Transactional
    public void persist(
            List<DenseChunkSnapshot> snapshot,
            List<EmbeddedKnowledgeChunk> pending,
            String provider,
            String model
    ) {
        if (snapshot == null || snapshot.isEmpty()) {
            throw new ConflictException("Dense refresh snapshot must not be empty");
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }

        Map<Long, DenseChunkSnapshot> snapshotById = new HashMap<>();
        for (DenseChunkSnapshot value : snapshot) {
            if (value.chunk().getId() == null || snapshotById.put(value.chunk().getId(), value) != null) {
                throw new ConflictException("Dense refresh snapshot contains duplicate chunk identity");
            }
        }

        Map<Long, KnowledgeChunk> currentById = new HashMap<>();
        Set<Long> ids = snapshotById.keySet();
        for (KnowledgeChunk current : chunkRepository.findAllById(ids)) {
            if (current != null && current.getId() != null) {
                currentById.putIfAbsent(current.getId(), current);
            }
        }
        for (DenseChunkSnapshot expected : snapshot) {
            KnowledgeChunk current = currentById.get(expected.chunk().getId());
            if (!sameChunk(expected, current)) {
                throw new ConflictException(
                        "Knowledge chunk changed during dense refresh; no embeddings were persisted"
                );
            }
        }

        Map<Long, EmbeddedKnowledgeChunk> embeddedById = new HashMap<>();
        for (EmbeddedKnowledgeChunk embedded : pending) {
            if (embedded == null
                    || !Objects.equals(provider, embedded.provider())
                    || !Objects.equals(model, embedded.model())
                    || embedded.chunkId() == null
                    || embedded.projectId() == null
                    || embedded.materialId() == null
                    || embedded.chunkNo() == null
                    || embedded.dimensions() <= 0
                    || embedded.vector() == null
                    || embedded.vector().size() != embedded.dimensions()
                    || embedded.vector().stream().anyMatch(value -> value == null || !Double.isFinite(value))
                    || norm(embedded.vector()) == 0
                    || embeddedById.put(embedded.chunkId(), embedded) != null) {
                throw new ConflictException("Embedding provider returned an invalid refresh result");
            }
            DenseChunkSnapshot expected = snapshotById.get(embedded.chunkId());
            if (expected == null
                    || !Objects.equals(expected.chunk().getProjectId(), embedded.projectId())
                    || !Objects.equals(expected.chunk().getMaterialId(), embedded.materialId())
                    || !Objects.equals(expected.chunk().getChunkNo(), embedded.chunkNo())) {
                throw new ConflictException("Embedding result does not match the refresh snapshot");
            }
        }

        if (embeddedById.size() != pending.size()) {
            throw new ConflictException("Embedding provider returned duplicate refresh identities");
        }
        for (EmbeddedKnowledgeChunk embedded : pending) {
            embeddingStore.upsert(
                    currentById.get(embedded.chunkId()),
                    provider,
                    model,
                    embedded.vector()
            );
        }
    }

    private static boolean sameChunk(DenseChunkSnapshot expected, KnowledgeChunk current) {
        if (current == null || current.getId() == null) {
            return false;
        }
        return Objects.equals(expected.chunk().getId(), current.getId())
                && Objects.equals(expected.chunk().getProjectId(), current.getProjectId())
                && Objects.equals(expected.chunk().getMaterialId(), current.getMaterialId())
                && Objects.equals(expected.chunk().getChunkNo(), current.getChunkNo())
                && current.getContent() != null
                && Objects.equals(expected.contentHash(), KnowledgeEmbeddingStore.contentHash(current.getContent()));
    }

    private static double norm(List<Double> vector) {
        double squared = 0;
        for (double value : vector) {
            squared += value * value;
        }
        double norm = Math.sqrt(squared);
        if (!Double.isFinite(norm)) {
            throw new ConflictException("Embedding provider returned a non-finite vector");
        }
        return norm;
    }
}
