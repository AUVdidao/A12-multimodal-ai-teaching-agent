package com.auvdidao.a12teachingagent.domain.project;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "project_visits",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_visit_user_project", columnNames = {"user_id", "project_id"})
)
public class ProjectVisit extends BaseCreatedEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private LocalDateTime lastVisitedAt;

    @Column(nullable = false)
    private Integer visitCount;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public LocalDateTime getLastVisitedAt() {
        return lastVisitedAt;
    }

    public void setLastVisitedAt(LocalDateTime lastVisitedAt) {
        this.lastVisitedAt = lastVisitedAt;
    }

    public Integer getVisitCount() {
        return visitCount;
    }

    public void setVisitCount(Integer visitCount) {
        this.visitCount = visitCount;
    }
}
