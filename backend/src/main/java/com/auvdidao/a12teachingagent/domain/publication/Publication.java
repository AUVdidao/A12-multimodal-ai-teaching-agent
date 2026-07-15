package com.auvdidao.a12teachingagent.domain.publication;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "publications",
        indexes = {
                @Index(name = "idx_publication_approval_class", columnList = "approval_request_id,class_id"),
                @Index(name = "idx_publication_class_status", columnList = "class_id,status")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uk_publication_approval_class",
                columnNames = {"approval_request_id", "class_id"}
        )
)
public class Publication extends BaseCreatedEntity {

    @Column(name = "approval_request_id", nullable = false)
    private Long approvalRequestId;

    @Column(name = "artifact_version_id", nullable = false)
    private Long artifactVersionId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 5000)
    private String summary;

    @Column(name = "published_by", nullable = false)
    private Long publishedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PublicationStatus status;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    public Long getApprovalRequestId() {
        return approvalRequestId;
    }

    public void setApprovalRequestId(Long approvalRequestId) {
        this.approvalRequestId = approvalRequestId;
    }

    public Long getArtifactVersionId() {
        return artifactVersionId;
    }

    public void setArtifactVersionId(Long artifactVersionId) {
        this.artifactVersionId = artifactVersionId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getClassId() {
        return classId;
    }

    public void setClassId(Long classId) {
        this.classId = classId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public PublicationStatus getStatus() {
        return status;
    }

    public void setStatus(PublicationStatus status) {
        this.status = status;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(LocalDateTime withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }
}
