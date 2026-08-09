# Multi-Agent Runtime Contracts V1

## Current classification

The production system remains a Spring Boot–orchestrated, LLM-enhanced deterministic teaching workflow. The existing `agent/*` interfaces are business-stage contracts, not a running autonomous-agent runtime. Dense retrieval is integrated into the TeachingIntent production path at the base commit used for this change.

This change implements the typed contract layer only:

> Multi-Agent runtime contracts implemented; runtime orchestration not yet implemented.

## Contract definitions

- `AgentName`: `REQUIREMENT_CLARIFICATION`, `COURSE_OUTLINE`, `LESSON_PLAN`, `PPT_GENERATION`.
- `AgentStage`: lifecycle stages plus the four business stages and `HANDOFF`.
- `AgentRunStatus`: `PENDING`, `RUNNING`, `WAITING_FOR_TOOL`, `WAITING_FOR_HUMAN`, `SUCCEEDED`, `FAILED`, `CANCELLED`.
- `AgentFailureKind`: validation, precondition, tool, model, retrieval, human, cancellation, and internal failure categories. Each kind exposes a default retryability policy; no retry loop is implemented.
- `BusinessRef`: immutable type/id/version/hash pointer. Hash is optional for current logical refs and required for `ARTIFACT_VERSION`.
- `GroundedEvidence`: positive chunk/material IDs, source filename, title, bounded excerpt, finite score, and `DENSE`/future `HYBRID` retrieval mode.
- `OutputRef`: bounded pointer and hash for prior output content; output bodies are not embedded in context.
- `AgentConstraints`: bounded lesson/output/slide/evidence/time/tool-call constraints.
- `RuntimeMetadata`: sanitized provider/model, warnings, tool-call IDs, and retrieved chunk IDs.
- `AgentContext`: immutable identity, business refs, evidence, prior output refs, constraints, and runtime metadata.
- `AgentRunSnapshot`: transport/persistence-neutral run snapshot with lifecycle consistency checks.
- `ToolResult<T>`: explicit success/failure result with typed data, failure kind, retryability, duration, source refs, and warnings.
- `AgentTool<I,O>` and `BoundedAgent<I,O>`: minimal future boundaries only.

## Bounds and immutability

Context trace IDs are at most 128 characters, run IDs are UUIDs, project IDs are positive, attempts are bounded, evidence is limited to 50 items, each excerpt is limited to 4096 characters, and previous outputs are limited to 20 references. Constraints additionally bound output types, lesson duration, requested slides, time budget, and tool calls. Lists are defensively copied with `List.copyOf` and cannot be mutated through the contracts.

## Security

The three context-bearing records have no secret-like fields for API keys, secrets, tokens, passwords, credentials, or bearer values. Runtime metadata is explicitly sanitized and bounded; raw prompts, complete material text, secrets, and chain-of-thought are outside the contract. `errorMessageSafe` is bounded and is not a stack-trace transport.

## Intentionally not implemented

This V1 does not implement agent execution, repositories, persistence tables, `AgentRun`/trace storage, an orchestrator, tool implementations, ModelGateway, model tool calling, memory, controllers, or changes to existing business services and PPT Harness code. Existing `agent/*` interfaces remain unchanged and the contracts are not Spring beans.

## Next stage

Stage 2 should introduce the persistence-neutral-to-persistent `AgentRun`/trace lifecycle and explicit handoff/resume semantics, with project authorization and human approval boundaries. It should consume these contracts without changing the current TeachingIntent, Dense RAG, GenerationPlan, Artifact, or PPT Harness behavior.
