package com.auvdidao.a12teachingagent.agent.runtime;

public enum AgentFailureKind {
    VALIDATION_FAILURE(false),
    PRECONDITION_FAILURE(false),
    TOOL_UNAVAILABLE(true),
    TOOL_TIMEOUT(true),
    MODEL_UNAVAILABLE(true),
    MODEL_INVALID_OUTPUT(true),
    RETRIEVAL_NOT_READY(false),
    HUMAN_REJECTED(false),
    CANCELLED(false),
    INTERNAL_FAILURE(false);

    private final boolean retryableDefault;

    AgentFailureKind(boolean retryableDefault) {
        this.retryableDefault = retryableDefault;
    }

    public boolean retryableDefault() {
        return retryableDefault;
    }
}
