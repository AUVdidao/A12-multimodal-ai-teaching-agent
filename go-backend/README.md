# LessonForge Go Backend

This is the new Mission-first business backend for the LessonForge Electron/Vue
teacher application. It is intentionally separate from the existing Java
worktrees. The existing RAG and PPT Engine remain external services and are
accessed only through adapters.

## Run

Set a persistent `LESSONFORGE_ENCRYPTION_KEY` (32 bytes as raw base64 or 64
hex characters), then run the Compose stack. PostgreSQL migrations are applied
by the server on startup.

```text
GET  /healthz
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
POST /api/uploads
POST /api/missions                 # first message + temporary upload binding
GET  /api/missions/{id}
POST /api/missions/{id}/messages
GET  /api/missions/{id}/events     # database-backed SSE
GET  /api/model-connections
POST /api/model-connections/{id}/verify
GET  /api/missions/{id}/planning/current
POST /api/planning/{draftId}/approve
GET  /api/missions/{id}/generation-jobs
GET  /api/missions/{id}/artifacts
```

All resources are owner-checked. API keys are encrypted with AES-GCM and never
appear in JSON responses, logs, activity events, AgentRun snapshots, or audit
records. The server does not read `MOONSHOT_API_KEY`, `KIMI_API_KEY`, or any
other global provider key.

The model client implements the OpenAI-compatible Chat Completions contract,
including URL normalization, HTTPS/DNS SSRF checks, same-authority redirect
checks, bounded response size, timeout handling, safe provider error mapping,
and `model`/Bearer request construction.

The Agent worker is DB-backed and restartable. It only runs when a teacher-owned
enabled and VERIFIED Model Connection is selected. Missing adapters or a
missing trusted PPT Engine execution package fail explicitly; no fake Agent,
RAG, or PPTX success is produced. If the PPT Engine returns an artifact receipt,
`LESSONFORGE_PPT_ENGINE_ARTIFACT_ROOT` must point to an explicitly shared,
read-only Engine output directory before the Go worker imports the artifact;
the relative storage key, size, and SHA-256 are verified before persistence.
