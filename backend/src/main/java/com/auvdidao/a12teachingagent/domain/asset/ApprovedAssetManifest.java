package com.auvdidao.a12teachingagent.domain.asset;

import com.auvdidao.a12teachingagent.asset.ManifestStatus;
import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "approved_asset_manifests")
public class ApprovedAssetManifest extends BaseCreatedEntity {
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @Column(name = "candidate_asset_id", nullable = false) private Long candidateAssetId;
    @Column(name = "asset_key", nullable = false, length = 128) private String assetKey;
    /** Nullable for REVOKED history; the database unique key permits only one active marker per project. */
    @Column(name = "active_key", length = 128) private String activeKey;
    @Column(name = "manifest_version", nullable = false) private Integer manifestVersion;
    @Column(name = "candidate_version", nullable = false) private Integer candidateVersion;
    @Column(name = "requirement_key", nullable = false, length = 128) private String requirementKey;
    @Column(name = "placement_intent", nullable = false, length = 32) private String placementIntent;
    @Column(name = "source_type", nullable = false, length = 32) private String sourceType;
    @Column(name = "source_reference", nullable = false, length = 500) private String sourceReference;
    @Column(name = "storage_key", nullable = false, length = 512) private String storageKey;
    @Column(name = "mime_type", nullable = false, length = 160) private String mimeType;
    @Column(name = "file_size", nullable = false) private Long fileSize;
    @Column(name = "sha256", nullable = false, length = 64) private String sha256;
    @Column(name = "approved_file_last_modified_utc", nullable = false, length = 64) private String approvedFileLastModifiedUtc;
    @Column(name = "provider", nullable = false, length = 32) private String provider;
    @Column(name = "model", nullable = false, length = 128) private String model;
    @Column(name = "approved_by", nullable = false) private Long approvedBy;
    @Column(name = "approved_at", nullable = false) private LocalDateTime approvedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ManifestStatus status;
    @Column(name = "supersedes_manifest_id") private Long supersedesManifestId;
    @Column(name = "manifest_checksum", nullable = false, length = 64) private String manifestChecksum;
    @Version @Column(name = "entity_version", nullable = false) private Long entityVersion;

    public Long getId() { return super.getId(); }
    public Long getProjectId() { return projectId; } public void setProjectId(Long v) { projectId = v; }
    public Long getOwnerUserId() { return ownerUserId; } public void setOwnerUserId(Long v) { ownerUserId = v; }
    public Long getCandidateAssetId() { return candidateAssetId; } public void setCandidateAssetId(Long v) { candidateAssetId = v; }
    public String getAssetKey() { return assetKey; } public void setAssetKey(String v) { assetKey = v; }
    public String getActiveKey() { return activeKey; } public void setActiveKey(String v) { activeKey = v; }
    public Integer getManifestVersion() { return manifestVersion; } public void setManifestVersion(Integer v) { manifestVersion = v; }
    public Integer getCandidateVersion() { return candidateVersion; } public void setCandidateVersion(Integer v) { candidateVersion = v; }
    public String getRequirementKey() { return requirementKey; } public void setRequirementKey(String v) { requirementKey = v; }
    public String getPlacementIntent() { return placementIntent; } public void setPlacementIntent(String v) { placementIntent = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType = v; }
    public String getSourceReference() { return sourceReference; } public void setSourceReference(String v) { sourceReference = v; }
    public String getStorageKey() { return storageKey; } public void setStorageKey(String v) { storageKey = v; }
    public String getMimeType() { return mimeType; } public void setMimeType(String v) { mimeType = v; }
    public Long getFileSize() { return fileSize; } public void setFileSize(Long v) { fileSize = v; }
    public String getSha256() { return sha256; } public void setSha256(String v) { sha256 = v; }
    public String getApprovedFileLastModifiedUtc() { return approvedFileLastModifiedUtc; } public void setApprovedFileLastModifiedUtc(String v) { approvedFileLastModifiedUtc = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public String getModel() { return model; } public void setModel(String v) { model = v; }
    public Long getApprovedBy() { return approvedBy; } public void setApprovedBy(Long v) { approvedBy = v; }
    public LocalDateTime getApprovedAt() { return approvedAt; } public void setApprovedAt(LocalDateTime v) { approvedAt = v; }
    public ManifestStatus getStatus() { return status; } public void setStatus(ManifestStatus v) { status = v; }
    public Long getSupersedesManifestId() { return supersedesManifestId; } public void setSupersedesManifestId(Long v) { supersedesManifestId = v; }
    public String getManifestChecksum() { return manifestChecksum; } public void setManifestChecksum(String v) { manifestChecksum = v; }
    public Long getEntityVersion() { return entityVersion; } public void setEntityVersion(Long v) { entityVersion = v; }
}
