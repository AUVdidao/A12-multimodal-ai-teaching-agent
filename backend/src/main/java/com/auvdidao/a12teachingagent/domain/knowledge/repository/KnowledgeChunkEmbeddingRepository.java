package com.auvdidao.a12teachingagent.domain.knowledge.repository;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeChunkEmbeddingRepository extends JpaRepository<KnowledgeChunkEmbedding, Long> {

    Optional<KnowledgeChunkEmbedding> findByKnowledgeChunkId(Long knowledgeChunkId);

    List<KnowledgeChunkEmbedding> findAllByProjectIdOrderByKnowledgeChunkIdAsc(Long projectId);

    List<KnowledgeChunkEmbedding> findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
            Long projectId,
            String provider,
            String model
    );

    long countByProjectId(Long projectId);

    long deleteByKnowledgeChunkId(Long knowledgeChunkId);

    long deleteByMaterialId(Long materialId);

    long deleteByProjectId(Long projectId);
}
