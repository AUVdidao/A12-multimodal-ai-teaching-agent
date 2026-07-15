package com.auvdidao.a12teachingagent.domain.approval;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "approval_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_active_artifact_approval",
                columnNames = "active_artifact_version_id"
        )
)
public class ApprovalRequest extends BaseAuditableEntity {

    @Column(name = "artifact_version_id", nullable = false)
    private Long artifactVersionId;

    @Column(name = "active_artifact_version_id")
    private Long activeArtifactVersionId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApprovalStatus status;

    @Column(length = 5000)
    private String reviewNote;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    private LocalDateTime reviewedAt;

    public Long getArtifactVersionId() {
        return artifactVersionId;
    }

    public void setArtifactVersionId(Long artifactVersionId) {
        this.artifactVersionId = artifactVersionId;
    }

    public Long getActiveArtifactVersionId() {
        return activeArtifactVersionId;
    }

    public void setActiveArtifactVersionId(Long activeArtifactVersionId) {
        this.activeArtifactVersionId = activeArtifactVersionId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(Long submittedBy) {
        this.submittedBy = submittedBy;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public void setStatus(ApprovalStatus status) {
        this.status = status;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
