package com.auvdidao.a12teachingagent.ai.connection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class ModelConnectionDtos {
    private ModelConnectionDtos() { }

    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull ModelConnectionProtocol protocol,
            @NotBlank @Size(max = 2048) String baseUrl,
            @NotBlank @Size(max = 512) String apiKey,
            @NotBlank @Size(max = 128) String modelId
    ) { }

    public record UpdateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull ModelConnectionProtocol protocol,
            @NotBlank @Size(max = 2048) String baseUrl,
            @Size(max = 512) String apiKey,
            @NotBlank @Size(max = 128) String modelId
    ) { }

    public record ConnectionView(
            Long id, String name, ModelConnectionProtocol protocol, String baseUrl, String modelId,
            String keyHint, boolean enabled, ModelConnectionVerificationStatus verificationStatus,
            LocalDateTime lastVerifiedAt, LocalDateTime lastUsedAt, LocalDateTime createdAt, LocalDateTime updatedAt
    ) { }

    public record VerificationView(Long connectionId, ModelConnectionVerificationStatus status, String safeCode,
                                   int httpStatus, String baseUrlHost, String modelId, LocalDateTime verifiedAt) { }
}
