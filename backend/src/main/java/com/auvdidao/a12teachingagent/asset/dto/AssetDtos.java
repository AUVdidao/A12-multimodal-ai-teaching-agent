package com.auvdidao.a12teachingagent.asset.dto;

import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.asset.AssetStatus;
import com.auvdidao.a12teachingagent.asset.ManifestStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class AssetDtos {
    private AssetDtos() {}

    public record CreateUploadCandidateRequest(
            @NotBlank @Size(max = 128) String assetKey,
            @NotBlank @Size(max = 128) String requirementKey,
            @NotBlank @Size(max = 32) String placementIntent,
            @NotNull @Positive Long materialId,
            @NotBlank @Size(min = 64, max = 64) String sha256
    ) {}

    public record GenerateCandidateRequest(
            @NotBlank @Size(max = 128) String assetKey,
            @NotBlank @Size(max = 128) String requirementKey,
            @NotBlank @Size(max = 32) String placementIntent,
            @NotNull ModelProvider provider,
            @NotBlank @Size(max = 128) String model
    ) {}

    public record ReviewRequest(@Size(max = 1000) String reason, @Size(min = 64, max = 64) String expectedSha256) {}

    public record CandidateResponse(Long id, Long projectId, String assetKey, int versionNumber,
                                    String requirementKey, String placementIntent, String sourceType,
                                    String sourceReference, String mimeType, long fileSize, String sha256,
                                    String provider, String model, AssetStatus status, Long reviewerUserId,
                                    LocalDateTime reviewedAt, String reviewReason) {}

    public record ManifestResponse(Long id, Long projectId, String assetKey, int manifestVersion,
                                   int candidateVersion, String requirementKey, String placementIntent,
                                   String sourceType, String sourceReference, String mimeType, long fileSize,
                                   String sha256, String provider, String model, ManifestStatus status,
                                   Long approvedBy, LocalDateTime approvedAt, String manifestChecksum) {}
}
