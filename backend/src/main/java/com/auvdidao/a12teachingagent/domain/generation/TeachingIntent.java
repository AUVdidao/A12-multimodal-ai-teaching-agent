package com.auvdidao.a12teachingagent.domain.generation;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "teaching_intents")
public class TeachingIntent extends BaseAuditableEntity {

    private Long projectId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String structuredContent;

    private Boolean confirmed;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getStructuredContent() {
        return structuredContent;
    }

    public void setStructuredContent(String structuredContent) {
        this.structuredContent = structuredContent;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }
}
