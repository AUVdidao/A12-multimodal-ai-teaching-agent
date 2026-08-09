package com.auvdidao.a12teachingagent.agent.tool;

import com.auvdidao.a12teachingagent.agent.runtime.AgentContext;
import com.auvdidao.a12teachingagent.agent.runtime.ToolResult;

/** Minimal future tool boundary; implementations are intentionally deferred. */
public interface AgentTool<I, O> {
    ToolResult<O> execute(I input, AgentContext context);
}
