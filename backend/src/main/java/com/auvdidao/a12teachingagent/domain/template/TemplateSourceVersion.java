package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "template_source_versions", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uk_template_sources_template_version", columnNames = {"template_id", "version_number"}),
        @jakarta.persistence.UniqueConstraint(name = "uk_template_sources_template_sha256", columnNames = {"template_id", "sha256"})
})
public class TemplateSourceVersion extends BaseAuditableEntity {

    private Long templateId;
    private Long projectId;
    private Integer versionNumber;
    private Long createdByUserId;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 255)
    private String storedFilename;

    @Column(nullable = false, length = 512)
    private String storageKey;

    @Column(nullable = false, length = 160)
    private String contentType;

    private Long fileSize;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    private TemplateProcessingStatus parseStatus = TemplateProcessingStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    private TemplateProcessingStatus renderStatus = TemplateProcessingStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    private TemplateProcessingStatus analysisStatus = TemplateProcessingStatus.NOT_STARTED;

    @Version
    private Long entityVersion;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getStoredFilename() { return storedFilename; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public TemplateProcessingStatus getParseStatus() { return parseStatus; }
    public void setParseStatus(TemplateProcessingStatus parseStatus) { this.parseStatus = parseStatus; }
    public TemplateProcessingStatus getRenderStatus() { return renderStatus; }
    public void setRenderStatus(TemplateProcessingStatus renderStatus) { this.renderStatus = renderStatus; }
    public TemplateProcessingStatus getAnalysisStatus() { return analysisStatus; }
    public void setAnalysisStatus(TemplateProcessingStatus analysisStatus) { this.analysisStatus = analysisStatus; }
    public Long getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Long entityVersion) { this.entityVersion = entityVersion; }
}

