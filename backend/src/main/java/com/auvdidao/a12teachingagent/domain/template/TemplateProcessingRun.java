package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "template_processing_runs")
public class TemplateProcessingRun extends BaseAuditableEntity {

    private Long templateId;
    private Long sourceVersionId;
    private Integer attempt;

    @Enumerated(EnumType.STRING)
    private TemplateProcessingOperation operation;

    @Enumerated(EnumType.STRING)
    private TemplateProcessingStatus status;

    @Column(length = 120)
    private String adapter;

    @Column(length = 512)
    private String outputReference;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String failureReason;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public TemplateProcessingOperation getOperation() { return operation; }
    public void setOperation(TemplateProcessingOperation operation) { this.operation = operation; }
    public TemplateProcessingStatus getStatus() { return status; }
    public void setStatus(TemplateProcessingStatus status) { this.status = status; }
    public String getAdapter() { return adapter; }
    public void setAdapter(String adapter) { this.adapter = adapter; }
    public String getOutputReference() { return outputReference; }
    public void setOutputReference(String outputReference) { this.outputReference = outputReference; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}


