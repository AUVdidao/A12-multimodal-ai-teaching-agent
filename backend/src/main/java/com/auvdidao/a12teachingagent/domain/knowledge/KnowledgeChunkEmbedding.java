package com.auvdidao.a12teachingagent.domain.knowledge;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "knowledge_chunk_embeddings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_knowledge_chunk_embedding_chunk_id",
                columnNames = "knowledge_chunk_id"
        ),
        indexes = {
                @Index(name = "idx_knowledge_chunk_embedding_project_id", columnList = "project_id"),
                @Index(name = "idx_knowledge_chunk_embedding_material_id", columnList = "material_id")
        }
)
public class KnowledgeChunkEmbedding extends BaseAuditableEntity {

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "knowledge_chunk_id", nullable = false)
    private Long knowledgeChunkId;

    @Column(nullable = false, length = 120)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 200)
    private String model;

    @Column(nullable = false)
    private Integer dimensions;

    @Lob
    @Column(name = "vector_json", columnDefinition = "TEXT", nullable = false)
    private String vector;

    @Column(nullable = false, length = 64)
    private String contentHash;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public Long getKnowledgeChunkId() {
        return knowledgeChunkId;
    }

    public void setKnowledgeChunkId(Long knowledgeChunkId) {
        this.knowledgeChunkId = knowledgeChunkId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }

    public String getVector() {
        return vector;
    }

    public void setVector(String vector) {
        this.vector = vector;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
