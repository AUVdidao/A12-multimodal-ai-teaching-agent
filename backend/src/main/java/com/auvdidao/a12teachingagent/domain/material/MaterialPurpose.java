package com.auvdidao.a12teachingagent.domain.material;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "material_purposes")
public class MaterialPurpose extends BaseCreatedEntity {

    private Long projectId;
    private Long materialId;

    @Enumerated(EnumType.STRING)
    private PurposeType purposeType;

    private String purposeDescription;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public PurposeType getPurposeType() {
        return purposeType;
    }

    public void setPurposeType(PurposeType purposeType) {
        this.purposeType = purposeType;
    }

    public String getPurposeDescription() {
        return purposeDescription;
    }

    public void setPurposeDescription(String purposeDescription) {
        this.purposeDescription = purposeDescription;
    }
}
