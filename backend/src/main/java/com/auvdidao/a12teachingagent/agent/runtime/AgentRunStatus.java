package com.auvdidao.a12teachingagent.agent.runtime;

public enum AgentRunStatus {
    PENDING,
    RUNNING,
    WAITING_FOR_TOOL,
    WAITING_FOR_HUMAN,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
