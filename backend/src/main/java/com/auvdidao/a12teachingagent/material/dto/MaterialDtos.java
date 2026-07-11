package com.auvdidao.a12teachingagent.material.dto;

import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class MaterialDtos {

    private MaterialDtos() {
    }

    public record MaterialResponse(
            Long id,
            Long projectId,
            String originalFilename,
            String fileExtension,
            MaterialFileType fileType,
            String contentType,
            Long fileSize,
            String description,
            UploadStatus uploadStatus,
            MaterialParseStatus parseStatus,
            List<PurposeType> usageTypes,
            String usageNote,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String downloadPath
    ) {
    }

    public record MaterialUsageUpdateRequest(
            @NotEmpty(message = "At least one material usage is required")
            List<@NotNull PurposeType> usageTypes,
            @Size(max = 500, message = "Usage note must be at most 500 characters")
            String note
    ) {
    }

    public record MaterialUsageResponse(
            Long materialId,
            Long projectId,
            List<PurposeType> usageTypes,
            String note,
            LocalDateTime updatedAt
    ) {
    }

    public record ParseResultResponse(
            Long id,
            Long materialId,
            MaterialParseStatus parseStatus,
            String summary,
            List<String> keywords,
            List<String> applicableTeachingStages,
            String failureReason,
            LocalDateTime parsedAt,
            boolean prototype
    ) {
    }
}
