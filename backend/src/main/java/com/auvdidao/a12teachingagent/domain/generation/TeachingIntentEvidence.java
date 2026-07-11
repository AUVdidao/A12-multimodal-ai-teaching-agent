package com.auvdidao.a12teachingagent.domain.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Lob;

@Embeddable
public class TeachingIntentEvidence {

    private Long materialId;
    private Long knowledgeChunkId;
    private String sourceFilename;
    private String usageTypes;
    private String hitReason;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String contentExcerpt;

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

    public String getSourceFilename() {
        return sourceFilename;
    }

    public void setSourceFilename(String sourceFilename) {
        this.sourceFilename = sourceFilename;
    }

    public String getUsageTypes() {
        return usageTypes;
    }

    public void setUsageTypes(String usageTypes) {
        this.usageTypes = usageTypes;
    }

    public String getHitReason() {
        return hitReason;
    }

    public void setHitReason(String hitReason) {
        this.hitReason = hitReason;
    }

    public String getContentExcerpt() {
        return contentExcerpt;
    }

    public void setContentExcerpt(String contentExcerpt) {
        this.contentExcerpt = contentExcerpt;
    }
}
