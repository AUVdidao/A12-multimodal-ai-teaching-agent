package com.auvdidao.a12teachingagent.domain.mission;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "lessonforge_conversations", uniqueConstraints = @UniqueConstraint(name = "uk_lf_conversation_mission", columnNames = "mission_id"))
public class MissionConversation extends BaseAuditableEntity {
    @Column(name = "mission_id", nullable = false) private Long missionId;
    public Long getMissionId() { return missionId; }
    public void setMissionId(Long value) { missionId = value; }
}
