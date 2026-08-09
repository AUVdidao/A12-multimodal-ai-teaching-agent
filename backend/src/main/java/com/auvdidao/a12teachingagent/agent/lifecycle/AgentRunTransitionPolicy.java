package com.auvdidao.a12teachingagent.agent.lifecycle;

import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

final class AgentRunTransitionPolicy {
    private static final Map<AgentRunStatus, Set<AgentRunStatus>> ALLOWED = Map.of(
            AgentRunStatus.PENDING, EnumSet.of(AgentRunStatus.RUNNING, AgentRunStatus.CANCELLED),
            AgentRunStatus.RUNNING, EnumSet.of(
                    AgentRunStatus.WAITING_FOR_TOOL,
                    AgentRunStatus.WAITING_FOR_HUMAN,
                    AgentRunStatus.SUCCEEDED,
                    AgentRunStatus.FAILED,
                    AgentRunStatus.CANCELLED
            ),
            AgentRunStatus.WAITING_FOR_TOOL, EnumSet.of(
                    AgentRunStatus.RUNNING,
                    AgentRunStatus.FAILED,
                    AgentRunStatus.CANCELLED
            ),
            AgentRunStatus.WAITING_FOR_HUMAN, EnumSet.of(
                    AgentRunStatus.RUNNING,
                    AgentRunStatus.FAILED,
                    AgentRunStatus.CANCELLED
            ),
            AgentRunStatus.SUCCEEDED, Set.of(),
            AgentRunStatus.FAILED, Set.of(),
            AgentRunStatus.CANCELLED, Set.of()
    );

    private AgentRunTransitionPolicy() {
    }

    static void requireAllowed(AgentRunStatus current, AgentRunStatus target) {
        if (current == null || target == null || !ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new ConflictException("Agent run cannot transition from " + current + " to " + target);
        }
    }
}
