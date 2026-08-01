package com.auvdidao.a12teachingagent.clarification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClarificationAnswerRequest(
        @NotBlank @Size(max = 64) String questionId,
        @NotBlank @Size(max = 4000) String answer
) {
}
