package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "template_rendered_slide_sets")
public class TemplateRenderedSlideSet extends BaseCreatedEntity {

    private Long templateId;
    private Long sourceVersionId;
    private Long processingRunId;
    private Integer slideCount;

    @Column(length = 64)
    private String sourceSha256;

    @Column(length = 64)
    private String outputSha256;

    private Long outputSizeBytes;

    @Column(length = 120)
    private String adapterVersion;

    @Enumerated(EnumType.STRING)
    private TemplateProcessingStatus status;

    @Column(length = 512)
    private String previewReference;

    @Column(length = 500)
    private String statusMessage;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }
    public Long getProcessingRunId() { return processingRunId; }
    public void setProcessingRunId(Long processingRunId) { this.processingRunId = processingRunId; }
    public Integer getSlideCount() { return slideCount; }
    public void setSlideCount(Integer slideCount) { this.slideCount = slideCount; }
    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String sourceSha256) { this.sourceSha256 = sourceSha256; }
    public String getOutputSha256() { return outputSha256; }
    public void setOutputSha256(String outputSha256) { this.outputSha256 = outputSha256; }
    public Long getOutputSizeBytes() { return outputSizeBytes; }
    public void setOutputSizeBytes(Long outputSizeBytes) { this.outputSizeBytes = outputSizeBytes; }
    public String getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(String adapterVersion) { this.adapterVersion = adapterVersion; }
    public TemplateProcessingStatus getStatus() { return status; }
    public void setStatus(TemplateProcessingStatus status) { this.status = status; }
    public String getPreviewReference() { return previewReference; }
    public void setPreviewReference(String previewReference) { this.previewReference = previewReference; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
}

