package com.auvdidao.a12teachingagent.domain.specification;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ppt_specification_versions", uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "uk_ppt_specifications_project_version", columnNames = {"project_id", "version_number"}))
public class PptSpecificationVersion extends BaseAuditableEntity {

    @Column(nullable = false, length = 128)
    private String specificationId;
    @Column(nullable = false)
    private Long projectId;
    @Column(nullable = false)
    private Integer versionNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PptSpecificationStatus status;
    @Column(nullable = false, length = 16)
    private String contractVersion;
    @Column(nullable = false, length = 128)
    private String templateProfileId;
    @Column(nullable = false)
    private Integer templateProfileVersion;
    @Column(length = 64)
    private String templateProfileChecksum;
    @Column(nullable = false)
    private Integer templateCapabilityViewVersion;
    @Column(nullable = false, length = 64)
    private String templateCapabilityViewChecksum;
    @Column(nullable = false)
    private Integer targetSlideCount;
    @Column(nullable = false)
    private Integer slideCountTolerance;
    @Column(nullable = false, length = 32)
    private String locale;
    @Column(nullable = false, length = 64)
    private String provider;
    @Column(nullable = false, length = 128)
    private String model;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiSupplementPolicy aiSupplementPolicy;
    @Column
    private Long createdBy;
    private Long updatedBy;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    @Column(length = 64)
    private String submittedChecksum;
    private Long lockedBy;
    private LocalDateTime lockedAt;
    @Column(nullable = false, length = 64)
    private String checksum;
    private Integer returnedFromVersion;
    @Column(length = 2000)
    private String returnReason;
    private LocalDateTime teacherEditingAt;

    @Version
    @Column(nullable = false)
    private Long entityVersion;

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position asc")
    private List<PptSpecificationSlide> slides = new ArrayList<>();

    public void addSlide(PptSpecificationSlide slide) {
        slide.setVersion(this);
        slides.add(slide);
    }

    public void clearSlides() {
        slides.clear();
    }

    public Long getEntityVersion() { return entityVersion; }
    public String getSpecificationId() { return specificationId; }
    public void setSpecificationId(String specificationId) { this.specificationId = specificationId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public PptSpecificationStatus getStatus() { return status; }
    public void setStatus(PptSpecificationStatus status) { this.status = status; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String contractVersion) { this.contractVersion = contractVersion; }
    public String getTemplateProfileId() { return templateProfileId; }
    public void setTemplateProfileId(String templateProfileId) { this.templateProfileId = templateProfileId; }
    public Integer getTemplateProfileVersion() { return templateProfileVersion; }
    public void setTemplateProfileVersion(Integer templateProfileVersion) { this.templateProfileVersion = templateProfileVersion; }
    public String getTemplateProfileChecksum() { return templateProfileChecksum; }
    public void setTemplateProfileChecksum(String templateProfileChecksum) { this.templateProfileChecksum = templateProfileChecksum; }
    public Integer getTemplateCapabilityViewVersion() { return templateCapabilityViewVersion; }
    public void setTemplateCapabilityViewVersion(Integer templateCapabilityViewVersion) { this.templateCapabilityViewVersion = templateCapabilityViewVersion; }
    public String getTemplateCapabilityViewChecksum() { return templateCapabilityViewChecksum; }
    public void setTemplateCapabilityViewChecksum(String templateCapabilityViewChecksum) { this.templateCapabilityViewChecksum = templateCapabilityViewChecksum; }
    public Integer getTargetSlideCount() { return targetSlideCount; }
    public void setTargetSlideCount(Integer targetSlideCount) { this.targetSlideCount = targetSlideCount; }
    public Integer getSlideCountTolerance() { return slideCountTolerance; }
    public void setSlideCountTolerance(Integer slideCountTolerance) { this.slideCountTolerance = slideCountTolerance; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public AiSupplementPolicy getAiSupplementPolicy() { return aiSupplementPolicy; }
    public void setAiSupplementPolicy(AiSupplementPolicy aiSupplementPolicy) { this.aiSupplementPolicy = aiSupplementPolicy; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Long getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(Long submittedBy) { this.submittedBy = submittedBy; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getSubmittedChecksum() { return submittedChecksum; }
    public void setSubmittedChecksum(String submittedChecksum) { this.submittedChecksum = submittedChecksum; }
    public Long getLockedBy() { return lockedBy; }
    public void setLockedBy(Long lockedBy) { this.lockedBy = lockedBy; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public Integer getReturnedFromVersion() { return returnedFromVersion; }
    public void setReturnedFromVersion(Integer returnedFromVersion) { this.returnedFromVersion = returnedFromVersion; }
    public String getReturnReason() { return returnReason; }
    public void setReturnReason(String returnReason) { this.returnReason = returnReason; }
    public LocalDateTime getTeacherEditingAt() { return teacherEditingAt; }
    public void setTeacherEditingAt(LocalDateTime teacherEditingAt) { this.teacherEditingAt = teacherEditingAt; }
    public List<PptSpecificationSlide> getSlides() { return slides; }
}

