package com.auvdidao.a12teachingagent.approval.dto;

import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class ApprovalRequestDtos {

    private ApprovalRequestDtos() {
    }

    public record SubmitApprovalRequest(
            @NotNull @Positive Long projectId,
            @NotNull @Positive Long artifactVersionId,
            @NotNull @Positive Long reviewerId
    ) {
    }

    public record ReviewApprovalRequest(
            @NotNull ApprovalStatus status,
            @Size(max = 5000) String note
    ) {
    }

    public record ApprovalRequestResponse(
            Long id,
            Long artifactVersionId,
            Integer artifactVersionNumber,
            Long projectId,
            String projectName,
            Long submittedBy,
            String submittedByName,
            Long reviewerId,
            String reviewerName,
            ApprovalStatus status,
            String reviewNote,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
