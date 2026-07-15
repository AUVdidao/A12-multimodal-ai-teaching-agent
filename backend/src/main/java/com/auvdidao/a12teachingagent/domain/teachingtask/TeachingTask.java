package com.auvdidao.a12teachingagent.domain.teachingtask;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import com.auvdidao.a12teachingagent.domain.common.TaskPriority;
import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "teaching_tasks")
public class TeachingTask extends BaseAuditableEntity {

    @Column(nullable = false, length = 160)
    private String taskName;

    @Column(nullable = false)
    private Long courseId;

    private Long classId;

    @Column(nullable = false, length = 160)
    private String chapterTitle;

    @Column(nullable = false)
    private Long assigneeId;

    @Lob
    @Column(nullable = false)
    private String requirements;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority;

    @Column(nullable = false)
    private LocalDateTime dueAt;

    @Column(nullable = false)
    private Long createdBy;

    private Long linkedProjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TeachingTaskStatus taskStatus;

    @Lob
    private String submissionNote;

    @Lob
    private String reviewNote;

    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
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

    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getLinkedProjectId() {
        return linkedProjectId;
    }

    public void setLinkedProjectId(Long linkedProjectId) {
        this.linkedProjectId = linkedProjectId;
    }

    public TeachingTaskStatus getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(TeachingTaskStatus taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getSubmissionNote() {
        return submissionNote;
    }

    public void setSubmissionNote(String submissionNote) {
        this.submissionNote = submissionNote;
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
