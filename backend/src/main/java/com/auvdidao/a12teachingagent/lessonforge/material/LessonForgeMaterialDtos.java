package com.auvdidao.a12teachingagent.lessonforge.material;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class LessonForgeMaterialDtos {
    private LessonForgeMaterialDtos() {}

    public record IngestRequest(
            @NotNull @Positive Long missionId,
            @NotNull @Positive Long missionFileId,
            @NotNull @Positive Long ownerUserId,
            @NotNull @Positive Long actorUserId,
            @NotBlank @Size(min = 64, max = 64) String sourceSha256,
            @NotNull @Positive Long sourceSize,
            @Size(max = 300) String description
    ) {}

    /**
     * Identity proof required after intake. The internal service token only
     * authenticates the Go caller; it is not a substitute for this binding.
     */
    public record PipelineRequest(
            @NotNull @Positive Long missionId,
            @NotNull @Positive Long missionFileId,
            @NotNull @Positive Long ownerUserId,
            @NotNull @Positive Long actorUserId,
            @NotBlank @Size(min = 64, max = 64) String sourceSha256,
            @NotNull @Positive Long sourceSize
    ) {}

    public record IngestResponse(
            Long bindingId,
            Long missionId,
            Long missionFileId,
            Long projectId,
            Long materialId,
            String sourceSha256,
            Long sourceSize,
            String originalFilename,
            String bindingStatus,
            LocalDateTime createdAt
    ) {}
}
