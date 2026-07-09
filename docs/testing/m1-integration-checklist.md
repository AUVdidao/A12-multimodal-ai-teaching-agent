# M1 Integration Checklist

## 1. Scope

This checklist is used when integrating the M1 requirement clarification chain:

Home / project list -> new project -> generation mode selection -> teaching requirement input -> missing-field detection -> active clarification -> dialogue history -> refresh echo-back.

Current baseline:

- `main`: `801d5ea`
- TA-005: done on `main`
- TA-006: done on `main`
- TA-009: done on `main`
- TA-027: done on `main`
- TA-007 v2: pending merge
- TA-008 v2: pending merge

## 2. Pre-Merge Checks

- [ ] Confirm target branch is based on latest `main`.
- [ ] Confirm latest `main` contains `801d5ea`.
- [ ] Confirm the branch does not revert TA-005, TA-006, TA-009, or TA-027.
- [ ] Confirm the branch does not introduce real Dify keys or other secrets.
- [ ] Confirm the branch does not introduce Spring Cloud, Redis, MyBatis-Plus, object storage, or cloud deployment.
- [ ] Confirm the branch does not modify unrelated Docker deployment files unless the task explicitly requires it.
- [ ] Run `git diff --check`.
- [ ] Review changed files before merging.

## 3. TA-007 v2 Requirement Input Checks

Status:待对应分支合入后验证

- [ ] `POST /api/projects/{projectId}/requirements` exists.
- [ ] `GET /api/projects/{projectId}/requirements/latest` exists.
- [ ] Request fields align with the M1 contract:
  - `gradeLevel`
  - `subject`
  - `topic`
  - `lessonDuration`
  - `teachingGoals`
  - `keyPoints`
  - `difficultPoints`
  - `outputTypes`
  - `rawRequirementText`
- [ ] `topic` and `rawRequirementText` cannot both be empty.
- [ ] Optional fields do not block save.
- [ ] Requirement records are isolated by `projectId`.
- [ ] Latest requirement query returns the newest record for the same project only.
- [ ] Frontend route `/projects/:projectId/requirements` works from the main TA-006 flow.
- [ ] Manual `/requirements` entry does not break the main flow.
- [ ] Save success shows user feedback.
- [ ] Save failure shows user feedback.
- [ ] Refresh reloads the latest requirement.

## 4. TA-008 v2 Missing-Field And Clarification Checks

Status:待对应分支合入后验证

- [ ] Reuses TA-005 `AIWorkflowGateway` instead of creating a duplicate gateway.
- [ ] Does not connect to real Dify.
- [ ] Does not write or require real API keys.
- [ ] Does not implement TA-010 structured summary confirmation.
- [ ] Missing-field check endpoint matches the M1 contract.
- [ ] Clarification question endpoint matches the M1 contract.
- [ ] Response fields align with the M1 contract:
  - `complete`
  - `missingFields`
  - `questions`
- [ ] `missingFields` entries include:
  - `field`
  - `label`
  - `reason`
- [ ] Frontend can display missing fields.
- [ ] Frontend can display AI clarification questions.
- [ ] Mock mode is deterministic enough for demo and tests.

## 5. TA-009 Dialogue History Checks

Status:completed on current `main`

- [x] `POST /api/projects/{projectId}/dialogues` exists.
- [x] `GET /api/projects/{projectId}/dialogues` exists.
- [x] `GET /api/dialogues/{sessionId}` exists.
- [x] Dialogue request uses:
  - `sessionId`
  - `sender`
  - `content`
  - `roundNo`
- [x] `sender` supports `TEACHER`, `AI`, and `SYSTEM`.
- [x] Dialogue history is isolated by `projectId`.
- [x] Dialogue history is isolated by `sessionId`.
- [x] Message order is stable by `createdAt` and `id`.
- [x] Docker smoke verified TEACHER and AI message writes.
- [x] Docker smoke verified project-level and session-level queries.

## 6. Docker Reverification Checks

- [x] `docker compose config` passes on current `main`.
- [x] `docker compose build` passes on current `main`.
- [x] `docker compose up -d` starts backend and frontend containers.
- [x] Frontend responds at `http://localhost:8081/`.
- [x] `/api/ai-workflow/status` responds through frontend nginx proxy.
- [x] `/api/projects` responds through frontend nginx proxy.
- [x] `/api/model-modes` responds through frontend nginx proxy.
- [x] TA-009 dialogue APIs respond through frontend nginx proxy.
- [x] `docker compose logs backend --tail=100` has no startup error.
- [x] `docker compose logs frontend --tail=100` has no startup error.
- [x] `docker compose down` stops and removes containers.
- [x] Final `docker compose ps` is empty.

## 7. Full M1 Chain Checks

Status:partially available on current `main`; complete validation waits for TA-007 v2 and TA-008 v2.

- [x] Open home page.
- [x] Create a new project.
- [x] Select generation mode.
- [ ] Enter teaching requirement. Status:待对应分支合入后验证
- [ ] Save teaching requirement. Status:待对应分支合入后验证
- [ ] Refresh and echo latest requirement. Status:待对应分支合入后验证
- [ ] Detect missing fields. Status:待对应分支合入后验证
- [ ] Generate active clarification questions. Status:待对应分支合入后验证
- [x] Save dialogue messages.
- [x] Query dialogue history by project.
- [x] Query dialogue history by session.
- [ ] Verify complete browser route from requirement input to clarification. Status:待对应分支合入后验证
- [ ] Verify refresh echo-back across requirement and dialogue pages. Status:待对应分支合入后验证

## 8. Required Commands After M1 Merge

Run after TA-007 v2 and TA-008 v2 are merged:

```powershell
git status
git diff --check
cd backend
D:\pri_work\.tools\apache-maven-3.9.9\bin\mvn.cmd test
cd ..\frontend
npm.cmd run build
cd ..
docker compose config
docker compose build
docker compose up -d
powershell -ExecutionPolicy Bypass -File scripts/docker-smoke-test.ps1
docker compose logs backend --tail=100
docker compose logs frontend --tail=100
docker compose down
docker compose ps
```

## 9. Linear Backfill Template

Use this after full M1 integration:

```text
M1 requirement clarification integration verified.

Included capabilities:
- Project creation and model mode selection
- Requirement input save and latest echo-back
- Missing-field detection
- Mock clarification questions
- Dialogue history save and query
- Docker local prototype verification

Verification:
- Backend tests passed
- Frontend build passed
- Docker compose config/build/up passed
- Smoke test passed
- M1 browser/API chain passed

Known exclusions:
- Real Dify integration
- Material upload
- PPT/Word generation
- Structured requirement summary confirmation, unless TA-010 has also been merged
```
