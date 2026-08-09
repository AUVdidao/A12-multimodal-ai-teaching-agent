package com.auvdidao.a12teachingagent.agent.runtime;

import java.time.Instant;

/** Transport/persistence-neutral snapshot of one future agent run. */
public record AgentRunSnapshot(
        String runId,
        String traceId,
        String parentRunId,
        long projectId,
        AgentName agentName,
        AgentStage stage,
        AgentRunStatus status,
        int attempt,
        String inputHash,
        String outputHash,
        Instant startedAt,
        Instant endedAt,
        AgentFailureKind errorKind,
        String errorMessageSafe
) {
    public AgentRunSnapshot {
        runId = ContractValidation.uuidText(runId, "runId");
        traceId = ContractValidation.requiredText(traceId, "traceId", AgentContext.MAX_TRACE_ID_CHARS);
        parentRunId = ContractValidation.optionalUuidText(parentRunId, "parentRunId");
        ContractValidation.requirePositive(projectId, "projectId");
        if (agentName == null) {
            throw new IllegalArgumentException("agentName must not be null");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        ContractValidation.requireRange(attempt, 1, AgentContext.MAX_ATTEMPT, "attempt");
        inputHash = ContractValidation.optionalText(inputHash, "inputHash", 256);
        outputHash = ContractValidation.optionalText(outputHash, "outputHash", 256);
        errorMessageSafe = ContractValidation.optionalText(errorMessageSafe, "errorMessageSafe", 1_024);
        if (startedAt != null && endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (status == AgentRunStatus.SUCCEEDED && endedAt == null) {
            throw new IllegalArgumentException("SUCCEEDED run must have endedAt");
        }
        if (status == AgentRunStatus.SUCCEEDED && errorKind != null) {
            throw new IllegalArgumentException("SUCCEEDED run must not have errorKind");
        }
        if (status == AgentRunStatus.FAILED && errorKind == null) {
            throw new IllegalArgumentException("FAILED run must have errorKind");
        }
        if (status != AgentRunStatus.FAILED && status != AgentRunStatus.CANCELLED && errorKind != null) {
            throw new IllegalArgumentException("only FAILED or CANCELLED runs may have errorKind");
        }
        if (status == AgentRunStatus.CANCELLED && errorKind != null && errorKind != AgentFailureKind.CANCELLED) {
            throw new IllegalArgumentException("CANCELLED run may only have CANCELLED errorKind");
        }
    }
}
