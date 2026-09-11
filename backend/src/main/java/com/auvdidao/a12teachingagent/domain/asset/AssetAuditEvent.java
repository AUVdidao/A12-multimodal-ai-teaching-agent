package com.auvdidao.a12teachingagent.domain.asset;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "asset_audit_events")
public class AssetAuditEvent extends BaseCreatedEntity {
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "asset_id", nullable = false) private Long assetId;
    @Column(name = "actor_user_id", nullable = false) private Long actorUserId;
    @Column(name = "action", nullable = false, length = 32) private String action;
    @Column(name = "from_status", length = 16) private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 16) private String toStatus;
    @Column(name = "provider", nullable = false, length = 32) private String provider;
    @Column(name = "model", nullable = false, length = 128) private String model;
    @Column(name = "sha256", nullable = false, length = 64) private String sha256;
    @Column(name = "reason", length = 1000) private String reason;
    @Column(name = "event_checksum", nullable = false, length = 64) private String eventChecksum;

    public Long getId() { return super.getId(); }
    public void setProjectId(Long v) { projectId = v; } public Long getProjectId() { return projectId; }
    public void setAssetId(Long v) { assetId = v; } public Long getAssetId() { return assetId; }
    public void setActorUserId(Long v) { actorUserId = v; } public Long getActorUserId() { return actorUserId; }
    public void setAction(String v) { action = v; } public String getAction() { return action; }
    public void setFromStatus(String v) { fromStatus = v; } public String getFromStatus() { return fromStatus; }
    public void setToStatus(String v) { toStatus = v; } public String getToStatus() { return toStatus; }
    public void setProvider(String v) { provider = v; } public String getProvider() { return provider; }
    public void setModel(String v) { model = v; } public String getModel() { return model; }
    public void setSha256(String v) { sha256 = v; } public String getSha256() { return sha256; }
    public void setReason(String v) { reason = v; } public String getReason() { return reason; }
    public void setEventChecksum(String v) { eventChecksum = v; } public String getEventChecksum() { return eventChecksum; }
}
