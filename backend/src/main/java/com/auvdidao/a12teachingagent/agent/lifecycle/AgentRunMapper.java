package com.auvdidao.a12teachingagent.agent.lifecycle;

import com.auvdidao.a12teachingagent.agent.runtime.AgentRunSnapshot;
import com.auvdidao.a12teachingagent.domain.agent.AgentRun;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class AgentRunMapper {
    private AgentRunMapper() {
    }

    public static AgentRunSnapshot toSnapshot(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        return new AgentRunSnapshot(
                run.getRunId(),
                run.getTraceId(),
                run.getParentRunId(),
                run.getProjectId(),
                run.getAgentName(),
                run.getStage(),
                run.getStatus(),
                run.getAttempt(),
                run.getInputHash(),
                run.getOutputHash(),
                toInstant(run.getStartedAt()),
                toInstant(run.getEndedAt()),
                run.getFailureKind(),
                run.getErrorMessageSafe()
        );
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
