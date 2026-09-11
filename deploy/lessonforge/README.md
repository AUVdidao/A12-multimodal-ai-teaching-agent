# LessonForge unified container layout

This directory is the canonical control plane for the LessonForge Go bridge
and the A12/PPT services. The repository-relative build contexts in
`compose.yaml` are the source of truth for the published runtime; the
separate `D:\pri_work\lessonforge` directory is retained only for backups and
operational notes.

## Current transition state

The controlled cutover is complete. The active containers now belong to the
`lessonforge` Compose project and use the readable names below. The old
`a12-bridge` and `lessonforge-bridge` containers were stopped but deliberately
not removed, so they remain an immediate rollback point until the new stack has
passed its acceptance window.

The new `compose.yaml` intentionally reuses these existing resources:

- Go database volume: `lessonforge-bridge_lessonforge_pg`
- A12 database volume: `a12-bridge_postgres-data`
- shared application/Engine volume: `lessonforge-engine-shared`
- existing compatibility network: `lessonforge-a12-net`

The two PostgreSQL databases remain separate. This is required because their
schemas and migration histories are different.

## Readable container names

The target names are:

- `lessonforge-go-server`
- `lessonforge-go-postgres`
- `lessonforge-backend-api`
- `lessonforge-a12-postgres`
- `lessonforge-ppt-engine`
- `lessonforge-file-parser`
- `lessonforge-file-generator`
- `lessonforge-frontend`
- `lessonforge-reverse-proxy`
- `lessonforge-monitor-log` (optional `ops` profile)

Internal service DNS names such as `backend-api`, `database`, and
`ppt-engine-service` are retained so existing service-to-service contracts do
not change during the transition.

## Safe validation

Use `.env.example` only for syntax validation. The cutover loaded the current
runtime values directly from the old containers and did not write secrets into
this directory. Any future recreate operation must provide the current
persistent encryption/provider values through a protected environment or
secret store; do not copy secrets into this repository.

```powershell
docker compose --project-directory D:\pri_work\A12-ppt-stage34-integration\deploy\lessonforge --env-file D:\pri_work\A12-ppt-stage34-integration\deploy\lessonforge\.env.example config --quiet
```

The active stack can be inspected with:

```powershell
docker compose --project-directory D:\pri_work\A12-ppt-stage34-integration\deploy\lessonforge --env-file D:\pri_work\A12-ppt-stage34-integration\deploy\lessonforge\.env.example --project-name lessonforge ps
```

Do not run `down -v`; that could remove data if the command is changed later.

The cutover checklist is:

1. Back up both PostgreSQL databases and record the three volume names. Done.
2. Verify the new Compose config. Done.
3. Stop/recreate during a planned maintenance window. Done.
4. Confirm health checks, Go `/healthz`, A12 `/api/health`, parser, generator,
   PPT Engine, and the reverse proxy.
5. Run the real PPTX acceptance gate: generate, download, open in PowerPoint,
   save, close, and reopen.
6. Keep the old stopped containers until the acceptance evidence is complete.
