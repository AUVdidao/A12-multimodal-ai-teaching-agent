package com.auvdidao.a12teachingagent.clarification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClarificationAnswerRequest(
        @NotBlank @Size(max = 100) String targetField,
        @NotBlank @Size(max = 4000) String answer
) {
}
