package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;

/** Provider-neutral, safe model failure categories. Provider response bodies never cross this boundary. */
public enum ModelFailureKind {
    AUTHENTICATION(false),
    RATE_LIMIT(true),
    QUOTA(false),
    TIMEOUT(true),
    INVALID_OUTPUT(false),
    UPSTREAM(true),
    CONFIGURATION(false),
    INVALID_REQUEST(false),
    INTERRUPTED(false);

    private final boolean retryable;

    ModelFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public AgentFailureKind toAgentFailureKind() {
        return this == INVALID_OUTPUT
                ? AgentFailureKind.MODEL_INVALID_OUTPUT
                : AgentFailureKind.MODEL_UNAVAILABLE;
    }
}
