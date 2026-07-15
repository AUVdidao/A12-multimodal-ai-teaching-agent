package com.auvdidao.a12teachingagent.revision.dto;

import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactResponse;
import com.auvdidao.a12teachingagent.versioning.dto.ArtifactVersionDtos.ArtifactVersionResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class RevisionDtos {

    private RevisionDtos() {
    }

    public record RevisionRequest(
            @NotBlank(message = "instruction must not be blank")
            @Size(max = 4000, message = "instruction must not exceed 4000 characters")
            String instruction
    ) {
    }

    public record EditRecordResponse(
            Long id,
            Long projectId,
            Long versionId,
            String instruction,
            String resultSummary,
            LocalDateTime createdAt
    ) {
    }

    public record RevisionResponse(
            ArtifactVersionResponse version,
            List<ArtifactResponse> artifacts,
            String changeSummary,
            List<String> changedSections,
            String requestedProvider,
            String activeProvider,
            boolean mockProvider,
            String providerMessage,
            EditRecordResponse editRecord
    ) {
    }
}
