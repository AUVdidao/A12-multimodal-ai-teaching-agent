package com.auvdidao.a12teachingagent.agent.runtime;

import java.util.List;

/** Immutable bounded context passed between future agent executions. */
public record AgentContext(
        String traceId,
        String runId,
        String parentRunId,
        long projectId,
        String actorId,
        String actorRole,
        AgentName agentName,
        AgentStage stage,
        int attempt,
        BusinessRef requirementSummaryRef,
        BusinessRef teachingIntentRef,
        BusinessRef generationPlanRef,
        List<GroundedEvidence> evidence,
        List<OutputRef> previousOutputs,
        AgentConstraints constraints,
        RuntimeMetadata runtimeMetadata
) {
    public static final int MAX_TRACE_ID_CHARS = 128;
    public static final int MAX_EVIDENCE_ITEMS = 50;
    public static final int MAX_PREVIOUS_OUTPUTS = 20;
    public static final int MAX_ATTEMPT = 100;

    public AgentContext {
        traceId = ContractValidation.requiredText(traceId, "traceId", MAX_TRACE_ID_CHARS);
        runId = ContractValidation.uuidText(runId, "runId");
        parentRunId = ContractValidation.optionalUuidText(parentRunId, "parentRunId");
        ContractValidation.requirePositive(projectId, "projectId");
        actorId = ContractValidation.requiredText(actorId, "actorId", 128);
        actorRole = ContractValidation.requiredText(actorRole, "actorRole", 64);
        if (agentName == null) {
            throw new IllegalArgumentException("agentName must not be null");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        ContractValidation.requireRange(attempt, 1, MAX_ATTEMPT, "attempt");
        evidence = ContractValidation.immutableList(evidence, "evidence", MAX_EVIDENCE_ITEMS);
        previousOutputs = ContractValidation.immutableList(previousOutputs, "previousOutputs", MAX_PREVIOUS_OUTPUTS);
        constraints = constraints == null ? AgentConstraints.defaults() : constraints;
        runtimeMetadata = runtimeMetadata == null ? RuntimeMetadata.empty() : runtimeMetadata;
        if (evidence.size() > constraints.maxEvidenceItems()) {
            throw new IllegalArgumentException("evidence exceeds constraints.maxEvidenceItems");
        }
        for (GroundedEvidence item : evidence) {
            if (item.contentExcerpt().length() > constraints.maxEvidenceExcerptChars()) {
                throw new IllegalArgumentException("evidence contentExcerpt exceeds configured bound");
            }
        }
    }
}
