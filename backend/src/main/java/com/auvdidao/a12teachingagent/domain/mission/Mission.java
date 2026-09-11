package com.auvdidao.a12teachingagent.domain.mission;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "lessonforge_missions")
public class Mission extends BaseAuditableEntity {
    @Column(nullable = false, length = 200) private String title;
    @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, columnDefinition = "TEXT") private String description;
    @Column(name = "assigned_teacher_id", nullable = false) private Long assignedTeacherId;
    @Column(name = "created_by_leader_id", nullable = false) private Long createdByLeaderId;
    @Column private LocalDateTime deadline;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private MissionStatus status;
    @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "rejection_reason", columnDefinition = "TEXT") private String rejectionReason;
    @Column(name = "rejected_at") private LocalDateTime rejectedAt;
    @Column(name = "selected_model_connection_id") private Long selectedModelConnectionId;

    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public Long getAssignedTeacherId() { return assignedTeacherId; }
    public void setAssignedTeacherId(Long value) { assignedTeacherId = value; }
    public Long getCreatedByLeaderId() { return createdByLeaderId; }
    public void setCreatedByLeaderId(Long value) { createdByLeaderId = value; }
    public LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(LocalDateTime value) { deadline = value; }
    public MissionStatus getStatus() { return status; }
    public void setStatus(MissionStatus value) { status = value; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String value) { rejectionReason = value; }
    public LocalDateTime getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(LocalDateTime value) { rejectedAt = value; }
    public Long getSelectedModelConnectionId() { return selectedModelConnectionId; }
    public void setSelectedModelConnectionId(Long value) { selectedModelConnectionId = value; }
}
