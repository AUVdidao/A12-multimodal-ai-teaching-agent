# M1 Current Main Baseline Verification Report

## 1. Document Purpose

This document records the local health verification evidence for the current `main` baseline before the M1 requirement clarification chain is fully integrated. It is intended for technical review, Linear backfill, and follow-up integration planning.

This report is a baseline record only. It does not claim that TA-007, TA-008, or TA-010 capabilities are complete.

## 2. Current Main Version

- Verification date: 2026-07-09
- Baseline branch: `main`
- Current main commit: `801d5ea`
- Commit title: `Merge remote-tracking branch 'origin/docker-local-prototype-fix'`

The current `main` history includes:

- `801d5ea` - Docker local prototype baseline merged
- `19a6a94` - TA-009 dialogue history branch merged
- `8c8ac1b` - TA-006 project entry flow connected
- `53d4faf` - TA-005 mock AI workflow contract added

## 3. Merged Tasks

- TA-005: Mock AI Workflow / AIWorkflowGateway
- TA-006: Project creation and generation mode selection flow
- TA-009: Dialogue history persistence
- TA-027: Local Docker prototype deployment baseline

## 4. Not Yet Merged Tasks

- TA-007: Requirement input v2
- TA-008: Clarification mock / missing-field question v2

These tasks are still under repair and must not be treated as part of the current `main` demonstration baseline.

## 5. Local Verification Commands And Results

### Git Baseline

Commands:

```powershell
git fetch --all
git checkout main
git merge --ff-only origin/main
git status
git log --oneline -10
```

Result:

- `git fetch --all` failed through SSH because the connection to port 22 was closed.
- Remote refs were refreshed through GitHub HTTPS token without printing the token.
- `main` was already up to date with `origin/main`.
- Working tree was clean before verification.
- Required commits `801d5ea`, `19a6a94`, `8c8ac1b`, and `53d4faf` were present.

### Backend Test

Commands:

```powershell
cd backend
mvn test
D:\pri_work\.tools\apache-maven-3.9.9\bin\mvn.cmd test
```

Result:

- `mvn test` failed because `mvn` is not available in the current PowerShell PATH.
- The equivalent local Maven command was used.
- The first local Maven run hit a local H2 runtime file issue:
  - `The database has been closed [90098-224]`
  - `MVStoreException`
- The local untracked H2 files under `backend/data` were backed up non-destructively to `D:\pri_work\A12-h2-backups\baseline-20260709-2136`.
- After using a clean local H2 runtime file, the same code baseline passed backend tests.

Final backend test result:

- Tests run: 19
- Failures: 0
- Errors: 0
- Skipped: 0
- Build result: `BUILD SUCCESS`

### Frontend Build

Commands:

```powershell
cd frontend
npm run build
npm.cmd run build
```

Result:

- `npm run build` was blocked by the local PowerShell execution policy for `npm.ps1`.
- `npm.cmd run build` was used as the equivalent command.
- Vue type check and Vite production build passed.
- Existing warnings:
  - Rollup annotation warning from `@vueuse/core`
  - Chunk size warning for the production bundle

Final frontend build result: passed.

### Git Cleanliness

Commands:

```powershell
cd ..
git diff --check
git status
```

Result:

- `git diff --check`: passed.
- `git status`: clean before adding this document.

## 6. Docker Verification Commands And Results

### Docker Compose Config

Command:

```powershell
docker compose config
```

Result:

- Passed.
- `AI_PROVIDER` / `A12_AI_PROVIDER` resolved to mock mode.
- `DIFY_API_KEY` was empty.
- Frontend exposed `8081`.
- Backend exposed `8080`.
- Backend persisted data through the `backend-data` Docker volume.

### Docker Compose Build

Command:

```powershell
docker compose build
```

Result:

- Passed.
- Backend image built successfully.
- Frontend image built successfully.
- `.dockerignore` kept build contexts small.

### Docker Compose Up

Command:

```powershell
docker compose up -d
Start-Sleep -Seconds 15
docker compose ps
```

Result:

- Passed.
- Backend container was `Up`.
- Frontend container was `Up`.
- Port mappings:
  - Backend: `8080 -> 8080`
  - Frontend: `8081 -> 80`

### Docker Smoke Test

Command:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/docker-smoke-test.ps1
```

Result:

- Frontend `http://localhost:8081/`: passed.
- Mock AI status `http://localhost:8081/api/ai-workflow/status`: passed.
- Project list `http://localhost:8081/api/projects`: passed.
- Model modes `http://localhost:8081/api/model-modes`: passed.
- Final script result: `Docker smoke test passed.`

### TA-009 Dialogue Smoke

Manual smoke path:

1. Create a project through `POST /api/projects`.
2. Write one `TEACHER` dialogue through `POST /api/projects/{projectId}/dialogues`.
3. Write one `AI` dialogue through `POST /api/projects/{projectId}/dialogues`.
4. Query dialogues by project through `GET /api/projects/{projectId}/dialogues`.
5. Query dialogues by session through `GET /api/dialogues/{sessionId}`.

Result:

- Created project id: `5`
- Session id: `m1-baseline-5-session`
- TEACHER dialogue write: `code = 0`
- AI dialogue write: `code = 0`
- Project dialogue count: `2`
- Session dialogue count: `2`
- Sender order: `TEACHER`, `AI`

### Docker Logs

Commands:

```powershell
docker compose logs backend --tail=100
docker compose logs frontend --tail=100
```

Result:

- Backend started successfully with Spring Boot `3.3.6`.
- Backend active profile: `dev`.
- Backend H2 datasource started successfully.
- Backend Tomcat started on port `8080`.
- Frontend nginx started successfully.
- No startup errors were observed.
- Smoke-test requests returned HTTP 200.

### Docker Down

Commands:

```powershell
docker compose down
docker compose ps
```

Result:

- Containers stopped and removed successfully.
- Docker network removed successfully.
- Final `docker compose ps` output was empty.

## 7. Current Demonstrable Capabilities

The current `main` can demonstrate:

- Frontend access through `http://localhost:8081` in Docker mode.
- Backend startup in Docker mode.
- Mock AI Workflow status check through `/api/ai-workflow/status`.
- Project creation through `/api/projects`.
- Project list query through `/api/projects`.
- Generation mode options through `/api/model-modes`.
- TA-009 dialogue save through `/api/projects/{projectId}/dialogues`.
- TA-009 dialogue query by project through `/api/projects/{projectId}/dialogues`.
- TA-009 dialogue query by session through `/api/dialogues/{sessionId}`.
- Local Docker prototype build, startup, smoke test, and shutdown.

## 8. Capabilities That Must Not Be Claimed As Complete Yet

The current `main` must not claim completion of:

- Teaching requirement input save flow from TA-007 v2.
- Latest teaching requirement echo-back from TA-007 v2.
- Missing-field detection from TA-008 v2.
- Active clarification question generation from TA-008 v2.
- Structured requirement summary confirmation from TA-010.
- Material upload.
- RAG or multimodal material enhancement.
- PPT generation.
- Word document generation.
- Real Dify integration.
- Real large-model key integration.
- Production cloud deployment.

## 9. Next Waiting Items

- Wait for A12-7 v2 to be repaired and merged.
- Wait for A12-8 v2 to be repaired and merged.
- Run M1 total integration after both branches are merged.
- Re-run local tests, frontend build, Docker smoke test, and full M1 browser/API walkthrough after integration.

## 10. Acceptance Conclusion

Current `main` at `801d5ea` is a healthy baseline for the already merged scope:

- TA-005 Mock AI Workflow
- TA-006 Project entry and model mode flow
- TA-009 Dialogue history persistence
- TA-027 Local Docker prototype deployment

This baseline is suitable as the starting point for the next M1 integration pass, but it is not yet the final M1 requirement clarification chain because TA-007 v2 and TA-008 v2 are not merged.
