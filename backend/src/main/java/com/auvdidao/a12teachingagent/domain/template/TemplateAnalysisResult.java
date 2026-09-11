package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable audit record for one binding-aware Analyzer attempt. */
@Entity
@Table(name = "template_analysis_results")
public class TemplateAnalysisResult extends BaseAuditableEntity {
    private Long templateId;
    private Long projectId;
    private Long sourceVersionId;
    private Long ownerUserId;
    private Long processingRunId;

    @Column(nullable = false, unique = true, length = 128)
    private String analysisRunId;

    @Column(nullable = false, length = 64)
    private String sourceSha256;
    @Column(length = 64)
    private String parserSnapshotChecksum;
    private Long renderedSlideSetId;
    @Column(length = 512)
    private String renderedPreviewReference;
    @Column(length = 64)
    private String renderedOutputSha256;
    private Long renderedOutputSizeBytes;
    @Column(length = 120)
    private String adapter;
    @Column(length = 120)
    private String adapterVersion;
    @Column(length = 64)
    private String provider;
    @Column(length = 128)
    private String model;
    @Column(length = 64)
    private String inputSha256;
    @Column(length = 64)
    private String outputSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TemplateProcessingStatus status;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(columnDefinition = "TEXT")
    private String candidateProfileJson;

    @Column(length = 64)
    private String candidateProfileSha256;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(columnDefinition = "TEXT")
    private String failureReason;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long value) { templateId = value; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId = value; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long value) { sourceVersionId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getProcessingRunId() { return processingRunId; }
    public void setProcessingRunId(Long value) { processingRunId = value; }
    public String getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(String value) { analysisRunId = value; }
    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String value) { sourceSha256 = value; }
    public String getParserSnapshotChecksum() { return parserSnapshotChecksum; }
    public void setParserSnapshotChecksum(String value) { parserSnapshotChecksum = value; }
    public Long getRenderedSlideSetId() { return renderedSlideSetId; }
    public void setRenderedSlideSetId(Long value) { renderedSlideSetId = value; }
    public String getRenderedPreviewReference() { return renderedPreviewReference; }
    public void setRenderedPreviewReference(String value) { renderedPreviewReference = value; }
    public String getRenderedOutputSha256() { return renderedOutputSha256; }
    public void setRenderedOutputSha256(String value) { renderedOutputSha256 = value; }
    public Long getRenderedOutputSizeBytes() { return renderedOutputSizeBytes; }
    public void setRenderedOutputSizeBytes(Long value) { renderedOutputSizeBytes = value; }
    public String getAdapter() { return adapter; }
    public void setAdapter(String value) { adapter = value; }
    public String getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(String value) { adapterVersion = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getInputSha256() { return inputSha256; }
    public void setInputSha256(String value) { inputSha256 = value; }
    public String getOutputSha256() { return outputSha256; }
    public void setOutputSha256(String value) { outputSha256 = value; }
    public TemplateProcessingStatus getStatus() { return status; }
    public void setStatus(TemplateProcessingStatus value) { status = value; }
    public String getCandidateProfileJson() { return candidateProfileJson; }
    public void setCandidateProfileJson(String value) { candidateProfileJson = value; }
    public String getCandidateProfileSha256() { return candidateProfileSha256; }
    public void setCandidateProfileSha256(String value) { candidateProfileSha256 = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { failureReason = value; }
}
