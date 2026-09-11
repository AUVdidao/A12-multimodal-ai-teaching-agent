package com.auvdidao.a12teachingagent.domain.lessonforge;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Cross-service identity owned by the LessonForge integration boundary.
 * Mission and MissionFile identifiers are Go-owned and therefore are stored
 * as immutable references without cross-database foreign keys.
 */
@Entity
@Table(name = "lessonforge_material_bindings")
public class LessonForgeMaterialBinding extends BaseAuditableEntity {

    @Column(name = "mission_id", nullable = false)
    private Long missionId;

    @Column(name = "mission_file_id", nullable = false)
    private Long missionFileId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "rag_project_id", nullable = false)
    private Long ragProjectId;

    @Column(name = "rag_material_id", nullable = false)
    private Long ragMaterialId;

    @Column(name = "source_sha256", nullable = false, length = 64)
    private String sourceSha256;

    @Column(name = "source_size", nullable = false)
    private Long sourceSize;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "binding_status", nullable = false, length = 20)
    private String bindingStatus;

    public Long getMissionId() { return missionId; }
    public void setMissionId(Long missionId) { this.missionId = missionId; }
    public Long getMissionFileId() { return missionFileId; }
    public void setMissionFileId(Long missionFileId) { this.missionFileId = missionFileId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public Long getRagProjectId() { return ragProjectId; }
    public void setRagProjectId(Long ragProjectId) { this.ragProjectId = ragProjectId; }
    public Long getRagMaterialId() { return ragMaterialId; }
    public void setRagMaterialId(Long ragMaterialId) { this.ragMaterialId = ragMaterialId; }
    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String sourceSha256) { this.sourceSha256 = sourceSha256; }
    public Long getSourceSize() { return sourceSize; }
    public void setSourceSize(Long sourceSize) { this.sourceSize = sourceSize; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getBindingStatus() { return bindingStatus; }
    public void setBindingStatus(String bindingStatus) { this.bindingStatus = bindingStatus; }
}
