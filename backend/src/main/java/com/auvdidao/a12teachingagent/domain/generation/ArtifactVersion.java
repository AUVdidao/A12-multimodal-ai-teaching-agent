package com.auvdidao.a12teachingagent.domain.generation;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "artifact_versions")
public class ArtifactVersion extends BaseCreatedEntity {

    private Long projectId;
    private Long generationPlanId;
    private Integer versionNumber;
    private String description;
    private Boolean finalVersion;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getGenerationPlanId() {
        return generationPlanId;
    }

    public void setGenerationPlanId(Long generationPlanId) {
        this.generationPlanId = generationPlanId;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(Integer versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getFinalVersion() {
        return finalVersion;
    }

    public void setFinalVersion(Boolean finalVersion) {
        this.finalVersion = finalVersion;
    }
}
