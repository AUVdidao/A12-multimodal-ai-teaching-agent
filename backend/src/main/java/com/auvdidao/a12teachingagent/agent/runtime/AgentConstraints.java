package com.auvdidao.a12teachingagent.agent.runtime;

import java.util.List;

/** Bounded, non-secret execution constraints supplied by the business workflow. */
public record AgentConstraints(
        Integer lessonDuration,
        List<String> outputTypes,
        Integer requestedSlideCount,
        int maxEvidenceItems,
        int maxEvidenceExcerptChars,
        long timeBudgetMs,
        int toolCallBudget
) {
    public static final int MAX_OUTPUT_TYPES = 16;
    public static final int MAX_OUTPUT_TYPE_CHARS = 64;
    public static final int MAX_EVIDENCE_ITEMS = 50;
    public static final int MAX_EVIDENCE_EXCERPT_CHARS = GroundedEvidence.MAX_EXCERPT_CHARS;
    public static final long MAX_TIME_BUDGET_MS = 86_400_000L;
    public static final int MAX_TOOL_CALL_BUDGET = 100;

    public AgentConstraints {
        if (lessonDuration != null) {
            ContractValidation.requireRange(lessonDuration, 1, 1_440, "lessonDuration");
        }
        outputTypes = ContractValidation.immutableTextList(
                outputTypes,
                "outputTypes",
                MAX_OUTPUT_TYPES,
                MAX_OUTPUT_TYPE_CHARS
        );
        if (requestedSlideCount != null) {
            ContractValidation.requireRange(requestedSlideCount, 1, 200, "requestedSlideCount");
        }
        ContractValidation.requireRange(maxEvidenceItems, 0, MAX_EVIDENCE_ITEMS, "maxEvidenceItems");
        ContractValidation.requireRange(
                maxEvidenceExcerptChars,
                1,
                MAX_EVIDENCE_EXCERPT_CHARS,
                "maxEvidenceExcerptChars"
        );
        ContractValidation.requireRange(timeBudgetMs, 1, MAX_TIME_BUDGET_MS, "timeBudgetMs");
        ContractValidation.requireRange(toolCallBudget, 0, MAX_TOOL_CALL_BUDGET, "toolCallBudget");
    }

    public static AgentConstraints defaults() {
        return new AgentConstraints(
                null,
                List.of(),
                null,
                MAX_EVIDENCE_ITEMS,
                MAX_EVIDENCE_EXCERPT_CHARS,
                300_000L,
                20
        );
    }
}
