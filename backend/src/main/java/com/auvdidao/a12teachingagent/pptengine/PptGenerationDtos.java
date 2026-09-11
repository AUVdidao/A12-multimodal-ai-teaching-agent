package com.auvdidao.a12teachingagent.pptengine;

import com.auvdidao.a12teachingagent.domain.generation.GenerationJobStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class PptGenerationDtos {
    private PptGenerationDtos() { }

    public record CreateGenerationJobRequest(
            @NotNull @Positive Long specificationVersionId,
            @NotBlank @Size(max = 128) String engineVersion,
            @NotBlank @Size(max = 128) String idempotencyKey
    ) { }

    public record GenerationJobResponse(
            Long id,
            Long projectId,
            Long ownerUserId,
            Long requestedBy,
            String executionId,
            String idempotencyKey,
            Long specificationVersionId,
            Integer specificationVersion,
            String specificationChecksum,
            Long templateProfileId,
            Integer templateProfileVersion,
            String templateProfileChecksum,
            Integer assetManifestVersion,
            String assetManifestChecksum,
            String inputIdentityChecksum,
            String engineVersion,
            GenerationJobStatus status,
            String engineReceiptJson,
            String failureCode,
            String failureMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        /** Compatibility constructor for existing HTTP test fixtures and callers before the identity field was exposed. */
        public GenerationJobResponse(Long id, Long projectId, Long ownerUserId, Long requestedBy,
                                     String executionId, String idempotencyKey, Long specificationVersionId,
                                     Integer specificationVersion, String specificationChecksum, Long templateProfileId,
                                     Integer templateProfileVersion, String templateProfileChecksum, Integer assetManifestVersion,
                                     String assetManifestChecksum, String engineVersion, GenerationJobStatus status,
                                     String engineReceiptJson, String failureCode, String failureMessage,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) {
            this(id, projectId, ownerUserId, requestedBy, executionId, idempotencyKey, specificationVersionId,
                    specificationVersion, specificationChecksum, templateProfileId, templateProfileVersion,
                    templateProfileChecksum, assetManifestVersion, assetManifestChecksum, null, engineVersion,
                    status, engineReceiptJson, failureCode, failureMessage, createdAt, updatedAt);
        }
    }
}
