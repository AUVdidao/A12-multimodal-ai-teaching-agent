package model

// PlanAgentPromptVersion identifies the server-owned behavior contract sent to
// the planning model. It is frozen into every AgentRun snapshot so later
// prompt edits do not change the meaning of an already-created run.
const PlanAgentPromptVersion = "PLAN_AGENT_V1"

// PlanAgentContextPolicyVersion identifies how the durable Mission facts and
// conversation history are selected for one model call. Keep this separate
// from the prompt version because context trimming can change independently.
const PlanAgentContextPolicyVersion = "CONTEXT_V1"
