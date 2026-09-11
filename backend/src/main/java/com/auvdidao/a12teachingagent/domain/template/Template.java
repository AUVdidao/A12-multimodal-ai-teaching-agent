package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "templates", uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "uk_templates_project_name", columnNames = {"project_id", "name"}))
public class Template extends BaseAuditableEntity {

    private Long projectId;

    @Column(nullable = false, length = 200)
    private String name;

    private Long createdByUserId;
    private Long activeSourceVersionId;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Long getActiveSourceVersionId() {
        return activeSourceVersionId;
    }

    public void setActiveSourceVersionId(Long activeSourceVersionId) {
        this.activeSourceVersionId = activeSourceVersionId;
    }
}

