package com.auvdidao.a12teachingagent.clarification;

import java.util.List;

public record ClarificationResult(
        boolean complete,
        List<MissingField> missingFields,
        List<String> questions
) {
}
