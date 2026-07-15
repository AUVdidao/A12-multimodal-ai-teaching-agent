package com.auvdidao.a12teachingagent.versioning.dto;

import java.time.LocalDateTime;

public final class ArtifactVersionDtos {

    private ArtifactVersionDtos() {
    }

    public record ArtifactVersionResponse(
            Long id,
            Long projectId,
            Long generationPlanId,
            Integer versionNumber,
            String description,
            boolean finalVersion,
            int artifactCount,
            LocalDateTime createdAt
    ) {
    }
}
