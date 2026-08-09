package com.auvdidao.a12teachingagent.agent.runtime;

/** Future execution contract; no implementation or orchestration is introduced in V1. */
public interface BoundedAgent<I, O> {
    AgentName name();

    O execute(I input, AgentContext context);
}
