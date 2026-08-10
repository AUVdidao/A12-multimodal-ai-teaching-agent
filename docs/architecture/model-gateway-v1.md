# Provider-Neutral ModelGateway V1

## Status and boundary

Stage 3 defines the provider-neutral model-call boundary for future business agents. It does not execute an Agent, migrate the existing teaching workflow, implement RAG/tools, or change PPT Harness behavior. The current flow remains `RequirementSummary/TeachingIntent/GenerationPlan -> AIWorkflowGateway`; future agents will depend on `ModelGateway` rather than constructing `KimiChatClient`, HTTP clients, repositories, or credential lookups themselves.

## Contracts

`ModelExecutionContext` carries only trusted non-secret execution identity: `projectId`, `actorId`, `actorRole`, `traceId`, `runId`, `agentName`, bounded metadata, and `remainingBudgetMs`. It deliberately has no API key, token, password, credential, prompt, response, or reasoning field. `ModelRequest` bounds system instruction, messages, model hint, completion tokens, temperature, timeout, metadata, and tags. `ModelMessage` has `SYSTEM`, `USER`, and `ASSISTANT` roles. `ModelImageRef` accepts only bounded `http(s)` references; data URLs and inline base64 are rejected.

`ModelGateway` exposes three capabilities: `complete`, `completeStructured`, and `completeMultimodal`. `ModelResult`, `StructuredModelResult<T>`, and `MultimodalModelResult` carry provider/model/request ID, bounded content or typed value, finish reason, usage, duration, warnings, and repair state. They never carry raw provider JSON, prompt history, credentials, or chain-of-thought. `ModelUsage` is safe aggregate token metadata; the current Kimi transport returns zero usage when the provider omits usage fields.

## Provider adapters and policy

`MockModelGateway` is the only adapter in V1 and is deterministic for contract tests and future Agent tests. This stage deliberately does not add a real provider adapter, network client, provider endpoint, model allowlist, or provider-specific retry policy. A future adapter must enforce the effective HTTP timeout as `min(ModelRequest.timeoutMs, ModelExecutionContext.remainingBudgetMs)` and keep transport details behind this boundary.

Provider adapters must convert text messages and structured response format only inside the adapter. Structured calls must always parse JSON and validate the result locally with the same JSON Schema implementation used elsewhere in the backend. `RepairPolicy.NONE` is the default. `RepairPolicy.ONCE` permits exactly one bounded repair attempt after invalid JSON/schema/deserialization output; upstream/auth/timeout failures are not retried by this policy. Multimodal calls use the controlled image reference contract and do not persist image data in runtime context or trace. V1 implements these semantics in Mock only.

## Credential resolution

`ModelCredentialResolver` is detached-execution safe: it receives `actorId` from `ModelExecutionContext` and does not depend on a request-thread `SecurityContext`. It calls the existing explicit-owner `AiApiCredentialService.activeApiKey(Long)`, which preserves the three-slot, single-active-slot, AES/GCM encrypted, owner-scoped implementation. V1 does not pass the resolved secret to a real provider adapter. Resolution order is:

1. Current actor's active user credential.
2. Server environment fallback from `MOONSHOT_API_KEY` / `a12.kimi.api-key`.
3. Safe `MODEL_CREDENTIAL_MISSING` configuration failure.

The decrypted value exists only as a local value at the provider adapter call boundary. It is not copied into `ModelExecutionContext`, `ModelRequest`, `ModelResult`, `ModelFailureException`, `AgentTrace`, logs, or persistence. Actor A resolves only A's active credential; another actor receives only its own active credential or the server fallback.

## Failure taxonomy

`ModelFailureKind` is provider-neutral: `AUTHENTICATION`, `RATE_LIMIT`, `QUOTA`, `TIMEOUT`, `INVALID_OUTPUT`, `UPSTREAM`, `CONFIGURATION`, `INVALID_REQUEST`, and `INTERRUPTED`. A future adapter must map provider statuses to these categories and reduce provider body text to a stable safe code and generic message. `ModelFailureKind.toAgentFailureKind()` maps invalid structured output to `MODEL_INVALID_OUTPUT` and provider failures to `MODEL_UNAVAILABLE`; no rate-limit failure becomes `INTERNAL_FAILURE`, and timeout/rate-limit/upstream failures remain retryable by policy.

## Trace integration

`AgentTraceService.recordModelCall` records only provider, model, request ID, duration, and mapped failure kind against the already-started, project-scoped `AgentTrace`. A model call is not a tool call and is never written to `ToolCallTrace`. Prompt/response bodies, raw provider bodies, credentials, and reasoning are excluded. A successful repair attempt clears the prior invalid-output observation; the lifecycle run itself is still owned by the future orchestrator.

## Coexistence and non-goals

The old `AIWorkflowGateway`, `KimiAIWorkflowGateway`, `KimiStructuredExecutor`, current business Services, PPT Kimi provider, templates, SlideSpec, visual QA, RAG, and Agent interfaces are intentionally unchanged in behavior. This stage does not add a real Kimi adapter, business Agent classes, autonomous loops, tool calling, `RagTool`, `RequirementTool`, `ArtifactTool`, `PptTool`, controllers, memory, or a big-bang provider migration.

## Stage 4 direction

Stage 4 can add a bounded `RagTool` behind `AgentTool`, then a server-side Agent execution coordinator that creates/starts an `AgentRun`, builds `ModelExecutionContext` from the trusted run, calls `ModelGateway`, records safe model observations, and hands off through the existing lifecycle/HITL transitions. It should first add contract tests for tool budgets, retrieval source integrity, structured output fixtures, provider fallback policy, and trace retention before connecting a business Agent.
