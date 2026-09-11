package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "template_structural_snapshots")
public class TemplateStructuralSnapshot extends BaseCreatedEntity {

    private Long templateId;
    private Long sourceVersionId;
    private Long processingRunId;
    private Integer slideCount;

    @Column(nullable = false, length = 64)
    private String checksum;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }
    public Long getProcessingRunId() { return processingRunId; }
    public void setProcessingRunId(Long processingRunId) { this.processingRunId = processingRunId; }
    public Integer getSlideCount() { return slideCount; }
    public void setSlideCount(Integer slideCount) { this.slideCount = slideCount; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
}
