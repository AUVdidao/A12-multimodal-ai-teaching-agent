# Persisted AgentRun + Trace + HITL Lifecycle V1

## Scope and current classification

This change adds a persisted lifecycle substrate for future agents. It does not implement a business Agent, an orchestrator, a model gateway, a tool, memory, or PPT/RAG behavior.

The accurate status is:

> Persisted Agent runtime lifecycle implemented; Multi-Agent execution is not implemented.

The runtime service accepts an `AgentContext` that has already been built by a trusted server-side caller. `actorId` and `actorRole` are not accepted from a new client body in this stage.

## Persistence mechanism

The repository uses Spring Data JPA with Hibernate schema management: production uses `spring.jpa.hibernate.ddl-auto=update`, and tests use H2 `create-drop`. There is no Flyway or Liquibase mechanism in the current repository, so this change adds no migration file. Hibernate creates/updates `agent_runs`, `agent_traces`, `agent_tool_call_traces`, and their bounded element-collection tables.

The persistence model is separate from `AgentRunSnapshot`. `AgentRun` uses the existing auditable base classes and a JPA `@Version` column named `lock_version`; runtime-facing callers receive snapshots through `AgentRunMapper`.

## Data model

### AgentRun

`agent_runs` stores the database primary key separately from the UUID `runId`. It includes `traceId`, optional `parentRunId`, project and trusted actor identity, agent/stage/status/attempt, input/output hashes, lifecycle timestamps, failure fields, bounded safe error text, and optional `waitingReason` plus structured approval-target BusinessRef columns.

Indexes cover project, trace, parent, project+createdAt, and status. `runId` is unique. No prompt, model response, secret, credential, token, or reasoning field exists.

### AgentTrace

`agent_traces` is one row per run and is separate from `AgentRun`. It stores trace/run/project identity, agent/stage, optional provider/model, hashes, bounded retrieved chunk IDs, timestamps, duration, status, and failure kind. Retrieved chunk IDs use a bounded element collection rather than one unbounded JSON blob.

### ToolCallTrace

`agent_tool_call_traces` stores tool-call identity, trace/run/project scope, tool name, timestamps, duration, success, failure kind, retryability, bounded source identifiers, and warnings. It never stores tool input, full material, or output bodies. Source references are reduced to identifiers such as `TEACHING_INTENT:intent-1`.

## Transition graph

```text
PENDING
  | \
  |  +--> CANCELLED
  v
RUNNING
  |    |       |        |       \
  |    |       |        |        +--> CANCELLED
  |    |       |        +-----------> FAILED
  |    |       +--------------------> SUCCEEDED
  |    +--> WAITING_FOR_HUMAN --+--> RUNNING
  +------> WAITING_FOR_TOOL ----+--> RUNNING
                                  |
                                  +--> FAILED / CANCELLED
```

`SUCCEEDED`, `FAILED`, and `CANCELLED` are terminal. `WAITING_FOR_HUMAN` can only fail through `HUMAN_REJECTED`; the rejected run remains preserved and is never revived.

## Lifecycle semantics

- `PENDING`: `startedAt` and `endedAt` are null.
- `RUNNING`: `startedAt` is set and `endedAt` is null. `start` is idempotent when the run is already running.
- `WAITING_FOR_TOOL`: started run, no end timestamp. It represents an external precondition/tool wait and survives process restart.
- `WAITING_FOR_HUMAN`: started run, no end timestamp; `waitingReason` and optional structured approval target persist in the database.
- `resume`: only `WAITING_FOR_TOOL` and `WAITING_FOR_HUMAN` may resume to `RUNNING`; it clears the wait metadata and never revives a terminal run.
- `SUCCEEDED`: output hash may be recorded, `endedAt` is required, and failure fields are clear. Repeating the same succeed operation returns the current state; a different output hash is a conflict.
- `FAILED`: failure kind is required, `endedAt` is set, and the safe error message is normalized/truncated. A failed run is immutable; retry means creating a new run.
- `CANCELLED`: `failureKind=CANCELLED`, `endedAt` is set, and a pending run may be cancelled without a `startedAt`.

## Retry versus resume

`RETRIEVAL_NOT_READY.retryableDefault` remains `false`. A future orchestrator may map that precondition to `WAITING_FOR_TOOL` and call `resume` after index readiness. This is deliberately different from an automatic retry loop:

> retry policy is not resume-after-external-precondition.

No retry loop is implemented here.

## HITL and handoff

`WAITING_FOR_HUMAN` is a persisted status, not an in-memory Future, thread, or HTTP-session state. Human rejection transitions the current run to `FAILED(HUMAN_REJECTED)`. A new run must be created for another attempt; the old run and trace remain queryable.

`createChildRun` requires a project-scoped `SUCCEEDED` parent, creates a new UUID `runId`, preserves the parent `traceId`, sets `parentRunId`, uses the same project, and starts with attempt 1. Cross-project parent lookup is rejected by project-scoped repository access.

## Trace continuity and safe querying

An execution chain keeps one `traceId` while each stage receives a distinct `runId`. Runtime reads use `ProjectAccessService` and repository queries containing `projectId`; no public Controller is added in V1. Recent run/trace queries have a bounded limit of 100.

`AgentTraceService` owns run-start, run-completion, and tool-call trace writes. It records IDs, hashes, metrics, status, and safe bounded summaries only. Automatic retention is not implemented; future work must add retention before trace volume becomes unbounded.

## Concurrency and idempotency

`AgentRun` uses JPA optimistic locking via `@Version lock_version`. Every transition uses `saveAndFlush`; a stale concurrent update becomes a conflict instead of silently overwriting another terminal state. Identical repeated terminal success/cancel/failure requests return the current state where safe; conflicting terminal operations are rejected.

## Security boundaries

All lifecycle reads and writes are project-scoped through the existing `ProjectAccessService`. The lifecycle service receives a server-built `AgentContext`; no controller is present to accept actor identity from a client body. Error messages are whitespace-normalized, truncated to 1024 characters, and reject obvious API-key/secret/password/credential assignments, Bearer values, and stack-frame patterns. Trace entities have reflection tests guarding against raw prompt/response, credential, token, Bearer, reasoning, and chain-of-thought fields.

## Intentionally not implemented

This stage does not implement RequirementClarificationAgent, CourseOutlineAgent, LessonPlanAgent, PptGenerationAgent, ModelGateway, RagTool, RequirementTool, ArtifactTool, PptTool, LLM tool calling, autonomous loops, memory, Hybrid/RRF, any PPT Harness change, or any `/agents`/`/agent-runs` Controller.

## Next stage

Stage 3 should define the ModelGateway contract and provider adapters behind this persisted lifecycle. It must not add real business-agent execution until model calls, structured outputs, failure mapping, and lifecycle handoff boundaries are independently specified.
