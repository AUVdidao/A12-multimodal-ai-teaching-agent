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
@Table(name = "lessonforge_submissions")
public class MissionSubmission extends BaseAuditableEntity {
    @Column(name = "mission_id", nullable = false) private Long missionId;
    @Column(name = "uploaded_by", nullable = false) private Long uploadedBy;
    @Column(name = "reviewer_id", nullable = false) private Long reviewerId;
    @Column(name = "original_file_name", nullable = false, length = 255) private String originalFileName;
    @Column(name = "content_type", nullable = false, length = 255) private String contentType;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(name = "storage_key", nullable = false, length = 500) private String storageKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private MissionSubmissionStatus status;
    private Integer rating;
    @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "review_note", columnDefinition = "TEXT") private String reviewNote;
    @Column(name = "reviewed_at") private LocalDateTime reviewedAt;
    public Long getMissionId() { return missionId; }
    public void setMissionId(Long value) { missionId = value; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long value) { uploadedBy = value; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long value) { reviewerId = value; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String value) { originalFileName = value; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { contentType = value; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long value) { fileSize = value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { storageKey = value; }
    public MissionSubmissionStatus getStatus() { return status; }
    public void setStatus(MissionSubmissionStatus value) { status = value; }
    public Integer getRating() { return rating; }
    public void setRating(Integer value) { rating = value; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String value) { reviewNote = value; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime value) { reviewedAt = value; }
}
