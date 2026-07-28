# ppt-harness

`ppt-harness` is A12's asynchronous, persistent presentation-generation coordinator. It owns template selection, SlideSpec validation, task state, controlled artifact storage, deterministic QA results, REST APIs, and MCP tools. The existing `ppt-skill-runner` remains responsible for rendering and presentation QA execution.

## Phase A scope

- One fixed teaching template: `a12-teaching-generic@1.0.0`.
- Fixture-only SlideSpec generation. Kimi and Dify are deliberately disabled.
- Persistent jobs in dedicated PostgreSQL schema `ppt_harness`.
- REST and MCP use the same `PresentationWorkflowService`.
- Generated artifacts are copied into controlled Harness storage. Internal runner paths are never returned. The QA endpoint returns a path-free quality summary rather than the raw runner report.
- QA level is explicitly `AUTOMATED_GEOMETRY_ONLY`; visual review states and interfaces are reserved but disabled.

## Local service

```powershell
docker compose up -d ppt-harness-postgres ppt-skill-runner ppt-harness
Invoke-RestMethod http://localhost:18091/health
Invoke-RestMethod http://localhost:18091/api/v1/presentation-templates
```

The harness REST API accepts asynchronous job creation at `POST /api/v1/presentation-jobs`. Status, event snapshots, artifact metadata, and controlled artifact download are exposed under `/api/v1/presentation-jobs/{taskId}`.

## Security boundaries

- No model API keys are read, stored, or required in Phase A.
- The stdio MCP process is intended for a trusted local host. A bearer token is reserved for a future network MCP transport and is not falsely claimed as active here.
- Runner paths, command lines, stack traces, arbitrary URLs, and PPTX base64 are not returned by REST or MCP.
- The schemas in `schema/` document the public TemplateSpec and SlideSpec contracts. Runtime validation additionally checks template layout, slot capacity, and forbidden placeholder text.
