package com.auvdidao.a12teachingagent.lessonforge.material;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class LessonForgeKnowledgeDtos {
    private LessonForgeKnowledgeDtos() {
    }

    public record SearchRequest(
            @NotNull Long missionId,
            @NotBlank String query,
            @Min(1) @Max(20) Integer limit,
            List<@NotNull Long> materialIds
    ) {
    }
}
