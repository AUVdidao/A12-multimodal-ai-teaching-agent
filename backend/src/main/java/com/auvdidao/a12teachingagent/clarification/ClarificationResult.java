package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationQuestion;

import java.util.List;

public record ClarificationResult(
        boolean complete,
        List<MissingField> missingFields,
        List<ClarificationQuestion> questions
) {
}
