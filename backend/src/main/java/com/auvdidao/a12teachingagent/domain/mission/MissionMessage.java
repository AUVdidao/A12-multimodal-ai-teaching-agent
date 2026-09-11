package com.auvdidao.a12teachingagent.domain.mission;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "lessonforge_messages")
public class MissionMessage extends BaseCreatedEntity {
    @Column(name = "mission_id", nullable = false) private Long missionId;
    @Column(name = "conversation_id", nullable = false) private Long conversationId;
    @Column(name = "sender_id", nullable = false) private Long senderId;
    @Column(name = "sender_role", nullable = false, length = 20) private String senderRole;
    @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, columnDefinition = "TEXT") private String content;
    public Long getMissionId() { return missionId; }
    public void setMissionId(Long value) { missionId = value; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { conversationId = value; }
    public Long getSenderId() { return senderId; }
    public void setSenderId(Long value) { senderId = value; }
    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String value) { senderRole = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
}
