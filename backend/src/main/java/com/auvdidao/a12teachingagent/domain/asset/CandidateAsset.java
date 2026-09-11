package com.auvdidao.a12teachingagent.domain.asset;

import com.auvdidao.a12teachingagent.asset.AssetStatus;
import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_assets")
public class CandidateAsset extends BaseAuditableEntity {
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @Column(name = "asset_key", nullable = false, length = 128) private String assetKey;
    @Column(name = "version_number", nullable = false) private Integer versionNumber;
    @Column(name = "requirement_key", nullable = false, length = 128) private String requirementKey;
    @Column(name = "placement_intent", nullable = false, length = 32) private String placementIntent;
    @Column(name = "source_type", nullable = false, length = 32) private String sourceType;
    @Column(name = "source_reference", nullable = false, length = 500) private String sourceReference;
    @Column(name = "storage_key", nullable = false, length = 512) private String storageKey;
    @Column(name = "original_file_reference", nullable = false, length = 128) private String originalFileReference;
    @Column(name = "mime_type", nullable = false, length = 160) private String mimeType;
    @Column(name = "file_size", nullable = false) private Long fileSize;
    @Column(name = "sha256", nullable = false, length = 64) private String sha256;
    @Column(name = "provider", nullable = false, length = 32) private String provider;
    @Column(name = "model", nullable = false, length = 128) private String model;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AssetStatus status;
    @Column(name = "reviewer_user_id") private Long reviewerUserId;
    @Column(name = "reviewed_at") private LocalDateTime reviewedAt;
    @Column(name = "review_reason", length = 1000) private String reviewReason;
    @Version @Column(name = "entity_version", nullable = false) private Long entityVersion;

    public Long getId() { return super.getId(); }
    public Long getProjectId() { return projectId; } public void setProjectId(Long v) { projectId = v; }
    public Long getOwnerUserId() { return ownerUserId; } public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getAssetKey() { return assetKey; } public void setAssetKey(String v) { assetKey = v; }
    public Integer getVersionNumber() { return versionNumber; } public void setVersionNumber(Integer v) { versionNumber = v; }
    public String getRequirementKey() { return requirementKey; } public void setRequirementKey(String v) { requirementKey = v; }
    public String getPlacementIntent() { return placementIntent; } public void setPlacementIntent(String v) { placementIntent = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType = v; }
    public String getSourceReference() { return sourceReference; } public void setSourceReference(String v) { sourceReference = v; }
    public String getStorageKey() { return storageKey; } public void setStorageKey(String v) { storageKey = v; }
    public String getOriginalFileReference() { return originalFileReference; } public void setOriginalFileReference(String v) { originalFileReference = v; }
    public String getMimeType() { return mimeType; } public void setMimeType(String v) { mimeType = v; }
    public Long getFileSize() { return fileSize; } public void setFileSize(Long v) { fileSize = v; }
    public String getSha256() { return sha256; } public void setSha256(String v) { sha256 = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public String getModel() { return model; } public void setModel(String v) { model = v; }
    public AssetStatus getStatus() { return status; } public void setStatus(AssetStatus v) { status = v; }
    public Long getReviewerUserId() { return reviewerUserId; } public void setReviewerUserId(Long v) { reviewerUserId = v; }
    public LocalDateTime getReviewedAt() { return reviewedAt; } public void setReviewedAt(LocalDateTime v) { reviewedAt = v; }
    public String getReviewReason() { return reviewReason; } public void setReviewReason(String v) { reviewReason = v; }
    public Long getEntityVersion() { return entityVersion; }
}
