package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "template_profile_versions", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uk_template_profiles_template_version", columnNames = {"template_id", "version_number"}),
        @jakarta.persistence.UniqueConstraint(name = "uk_template_profiles_analyzer_identity",
                columnNames = {"project_id", "template_id", "source_version_id", "analysis_run_id", "origin"})
})
public class TemplateProfileVersion extends BaseAuditableEntity {

    private Long templateId;
    private Long projectId;
    private Long sourceVersionId;
    private Long parentProfileVersionId;
    private Integer versionNumber;
    private Long createdByUserId;
    private Long ownedByTeacherId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TemplateProfileOrigin origin;

    @Column(length = 64)
    private String parserSnapshotChecksum;

    /** Server-owned material parse identities used to derive this profile. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(columnDefinition = "TEXT")
    private String materialParseBindingJson;

    @Column(length = 64)
    private String materialParseBindingChecksum;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TemplateProcessingStatus rendererStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TemplateProcessingStatus analyzerStatus;

    @Enumerated(EnumType.STRING)
    private TemplateProfileStatus status;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(nullable = false)
    private Integer capabilityViewVersion;

    @Column(nullable = false, length = 64)
    private String capabilityViewChecksum;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String profileJson;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String capabilityViewJson;

    /**
     * Server-derived native profile.  It is deliberately separate from the
     * semantic profile and capability view so Planning/teacher DTOs cannot
     * accidentally become the source of native object truth.
     */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(columnDefinition = "TEXT")
    private String engineNativeProfileJson;

    @Column(length = 64)
    private String engineNativeProfileChecksum;

    private LocalDateTime teacherEditedAt;
    private LocalDateTime confirmedAt;
    private String confirmedChecksum;

    @Column(length = 128)
    private String analysisRunId;
    @Column(length = 64)
    private String analyzerInputSha256;
    @Column(length = 64)
    private String analyzerOutputSha256;
    @Column(length = 64)
    private String renderedOutputSha256;
    private Long renderedOutputSizeBytes;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(columnDefinition = "TEXT")
    private String analyzerProposalJson;

    @Version
    private Long entityVersion;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }
    public Long getParentProfileVersionId() { return parentProfileVersionId; }
    public void setParentProfileVersionId(Long parentProfileVersionId) { this.parentProfileVersionId = parentProfileVersionId; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public Long getOwnedByTeacherId() { return ownedByTeacherId; }
    public void setOwnedByTeacherId(Long ownedByTeacherId) { this.ownedByTeacherId = ownedByTeacherId; }
    public TemplateProfileOrigin getOrigin() { return origin; }
    public void setOrigin(TemplateProfileOrigin origin) { this.origin = origin; }
    public String getParserSnapshotChecksum() { return parserSnapshotChecksum; }
    public void setParserSnapshotChecksum(String parserSnapshotChecksum) { this.parserSnapshotChecksum = parserSnapshotChecksum; }
    public String getMaterialParseBindingJson() { return materialParseBindingJson; }
    public void setMaterialParseBindingJson(String value) { materialParseBindingJson = value; }
    public String getMaterialParseBindingChecksum() { return materialParseBindingChecksum; }
    public void setMaterialParseBindingChecksum(String value) { materialParseBindingChecksum = value; }
    public TemplateProcessingStatus getRendererStatus() { return rendererStatus; }
    public void setRendererStatus(TemplateProcessingStatus rendererStatus) { this.rendererStatus = rendererStatus; }
    public TemplateProcessingStatus getAnalyzerStatus() { return analyzerStatus; }
    public void setAnalyzerStatus(TemplateProcessingStatus analyzerStatus) { this.analyzerStatus = analyzerStatus; }
    public TemplateProfileStatus getStatus() { return status; }
    public void setStatus(TemplateProfileStatus status) { this.status = status; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public Integer getCapabilityViewVersion() { return capabilityViewVersion; }
    public void setCapabilityViewVersion(Integer capabilityViewVersion) { this.capabilityViewVersion = capabilityViewVersion; }
    public String getCapabilityViewChecksum() { return capabilityViewChecksum; }
    public void setCapabilityViewChecksum(String capabilityViewChecksum) { this.capabilityViewChecksum = capabilityViewChecksum; }
    public String getProfileJson() { return profileJson; }
    public void setProfileJson(String profileJson) { this.profileJson = profileJson; }
    public String getCapabilityViewJson() { return capabilityViewJson; }
    public void setCapabilityViewJson(String capabilityViewJson) { this.capabilityViewJson = capabilityViewJson; }
    public String getEngineNativeProfileJson() { return engineNativeProfileJson; }
    public void setEngineNativeProfileJson(String value) { engineNativeProfileJson = value; }
    public String getEngineNativeProfileChecksum() { return engineNativeProfileChecksum; }
    public void setEngineNativeProfileChecksum(String value) { engineNativeProfileChecksum = value; }
    public LocalDateTime getTeacherEditedAt() { return teacherEditedAt; }
    public void setTeacherEditedAt(LocalDateTime teacherEditedAt) { this.teacherEditedAt = teacherEditedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getConfirmedChecksum() { return confirmedChecksum; }
    public void setConfirmedChecksum(String confirmedChecksum) { this.confirmedChecksum = confirmedChecksum; }
    public String getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(String value) { analysisRunId = value; }
    public String getAnalyzerInputSha256() { return analyzerInputSha256; }
    public void setAnalyzerInputSha256(String value) { analyzerInputSha256 = value; }
    public String getAnalyzerOutputSha256() { return analyzerOutputSha256; }
    public void setAnalyzerOutputSha256(String value) { analyzerOutputSha256 = value; }
    public String getRenderedOutputSha256() { return renderedOutputSha256; }
    public void setRenderedOutputSha256(String value) { renderedOutputSha256 = value; }
    public Long getRenderedOutputSizeBytes() { return renderedOutputSizeBytes; }
    public void setRenderedOutputSizeBytes(Long value) { renderedOutputSizeBytes = value; }
    public String getAnalyzerProposalJson() { return analyzerProposalJson; }
    public void setAnalyzerProposalJson(String value) { analyzerProposalJson = value; }
    public Long getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Long entityVersion) { this.entityVersion = entityVersion; }
}
