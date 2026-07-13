package com.auvdidao.a12teachingagent.dialog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public final class DialogDtos {

    private DialogDtos() {
    }

    public record DialogMessageRequest(
            @NotBlank String sessionId,
            @NotBlank String sender,
            @NotBlank String content,
            @NotNull @Min(1) Integer roundNo
    ) {
    }

    public record DialogMessageResponse(
            Long id,
            Long projectId,
            String sessionId,
            String sender,
            String content,
            Integer roundNo,
            LocalDateTime createdAt
    ) {
    }

    public record DialogClearResponse(
            Long projectId,
            long deletedCount
    ) {
    }
}
