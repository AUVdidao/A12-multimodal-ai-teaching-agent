package com.auvdidao.a12teachingagent.domain.generation;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "edit_records")
public class EditRecord extends BaseCreatedEntity {

    private Long projectId;
    private Long versionId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String editInstruction;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String editResult;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public String getEditInstruction() {
        return editInstruction;
    }

    public void setEditInstruction(String editInstruction) {
        this.editInstruction = editInstruction;
    }

    public String getEditResult() {
        return editResult;
    }

    public void setEditResult(String editResult) {
        this.editResult = editResult;
    }
}
