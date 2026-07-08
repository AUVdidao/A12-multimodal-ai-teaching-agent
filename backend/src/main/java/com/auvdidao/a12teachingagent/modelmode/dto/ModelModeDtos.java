package com.auvdidao.a12teachingagent.modelmode.dto;

import jakarta.validation.constraints.NotBlank;

public final class ModelModeDtos {

    private ModelModeDtos() {
    }

    public record ModelModeOption(
            String code,
            String name,
            String description
    ) {
    }

    public record ModelModeRequest(
            @NotBlank(message = "mode is required")
            String mode
    ) {
    }

    public record ProjectModelModeResponse(
            Long projectId,
            String mode,
            String name,
            String description
    ) {
    }
}
