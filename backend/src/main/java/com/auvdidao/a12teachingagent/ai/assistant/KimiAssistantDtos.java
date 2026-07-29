package com.auvdidao.a12teachingagent.ai.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class KimiAssistantDtos {
    private KimiAssistantDtos() { }

    public record ChatRequest(
            @NotBlank @Size(max = 4000) String message,
            @Size(max = 8) List<@Valid ConversationTurn> conversation
    ) {
        public ChatRequest {
            conversation = conversation == null ? List.of() : List.copyOf(conversation);
        }
    }

    public record ConversationTurn(
            @NotBlank @Pattern(regexp = "teacher|assistant") String role,
            @NotBlank @Size(max = 1600) String content
    ) { }

    public record ChatResponse(Long projectId, String provider, String model, String content) { }
}
