package com.auvdidao.a12teachingagent.domain.material;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "uploaded_materials")
public class UploadedMaterial extends BaseCreatedEntity {

    private Long projectId;
    private String fileName;
    private String originalFileName;

    @Enumerated(EnumType.STRING)
    private MaterialFileType fileType;

    private String filePath;
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    private UploadStatus uploadStatus;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public MaterialFileType getFileType() {
        return fileType;
    }

    public void setFileType(MaterialFileType fileType) {
        this.fileType = fileType;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public UploadStatus getUploadStatus() {
        return uploadStatus;
    }

    public void setUploadStatus(UploadStatus uploadStatus) {
        this.uploadStatus = uploadStatus;
    }
}
