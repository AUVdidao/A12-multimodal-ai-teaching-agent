package com.auvdidao.a12teachingagent.domain.knowledge;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "knowledge_chunks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_knowledge_chunk_material_no",
                columnNames = {"material_id", "chunk_no"}
        )
)
public class KnowledgeChunk extends BaseCreatedEntity {

    private Long projectId;
    private Long materialId;
    private Integer chunkNo;
    private String title;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String content;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "knowledge_chunk_keywords", joinColumns = @JoinColumn(name = "chunk_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "keyword_value")
    private List<String> keywords = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "knowledge_chunk_usages", joinColumns = @JoinColumn(name = "chunk_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "usage_type")
    @Enumerated(EnumType.STRING)
    private List<PurposeType> usageTypes = new ArrayList<>();

    private String sourceFilename;

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

    public Integer getChunkNo() {
        return chunkNo;
    }

    public void setChunkNo(Integer chunkNo) {
        this.chunkNo = chunkNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getKeywords() {
        return List.copyOf(keywords);
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? new ArrayList<>() : new ArrayList<>(keywords);
    }

    public List<PurposeType> getUsageTypes() {
        return List.copyOf(usageTypes);
    }

    public void setUsageTypes(List<PurposeType> usageTypes) {
        this.usageTypes = usageTypes == null ? new ArrayList<>() : new ArrayList<>(usageTypes);
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public void setSourceFilename(String sourceFilename) {
        this.sourceFilename = sourceFilename;
    }
}
