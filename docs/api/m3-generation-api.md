# A12 M3 Content Generation API

This document defines the persisted M3 generation contract used by the Vue frontend. All endpoints return the existing `ApiResponse<T>` envelope. The AI workflow endpoints under `/api/ai-workflow/**` are provider adapters and must not be called by business pages directly.

## 1. Workflow And Gates

```text
confirmed teaching intent
  -> create generation plan
  -> edit plan
  -> confirm plan
  -> generate normalized artifacts
  -> preview PPT, lesson plan, and interaction content
```

- A project without a confirmed teaching intent cannot create a plan.
- An unconfirmed plan cannot generate artifacts.
- The first successful generation creates version `v1`.
- Repeating generation for the same confirmed plan is idempotent and returns the existing artifacts.
- M3 returns normalized JSON only. Real `.pptx`, `.docx`, HTML, and ZIP files are rendered from a selected version in M4.
- `provider` is `MOCK` until the Dify provider is configured and successfully selected.

## 2. Shared Models

### 2.1 Plan section

```json
{
  "order": 1,
  "title": "Course opening",
  "description": "Introduce the topic and learning context"
}
```

### 2.2 Generation plan

```json
{
  "id": 31,
  "projectId": 12,
  "provider": "MOCK",
  "pptOutline": [],
  "docOutline": [],
  "interactionPlan": [],
  "confirmed": false,
  "createdAt": "2026-07-14T10:00:00",
  "updatedAt": "2026-07-14T10:00:00"
}
```

### 2.3 Artifact summary and preview

```json
{
  "id": 91,
  "projectId": 12,
  "generationPlanId": 31,
  "versionId": 18,
  "versionNumber": 1,
  "type": "PPT",
  "title": "Artificial intelligence foundations",
  "schemaVersion": 1,
  "content": {},
  "createdAt": "2026-07-14T10:05:00"
}
```

Artifact types are `PPT`, `DOCX`, and `INTERACTION`. `DOCX` is the persisted artifact type for a lesson-plan document; the M3 response is JSON and is not yet a downloadable Office file.

## 3. Workspace

### `GET /api/projects/{projectId}/generation/workspace`

Returns everything required to restore the generation page after refresh.

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "projectId": 12,
    "projectName": "Artificial intelligence foundations",
    "projectStatus": "INTENT_CONFIRMED",
    "provider": "MOCK",
    "teachingIntent": {
      "id": 7,
      "status": "CONFIRMED",
      "generationGoal": "Understand core AI concepts",
      "outputTypes": ["PPT", "LESSON_PLAN", "INTERACTION"]
    },
    "latestPlan": null,
    "artifacts": [],
    "capabilities": {
      "canCreatePlan": true,
      "canEditPlan": false,
      "canConfirmPlan": false,
      "canGenerate": false,
      "canPreview": false
    }
  },
  "timestamp": "2026-07-14T10:00:00"
}
```

## 4. Generation Plan

### `POST /api/projects/{projectId}/generation-plans`

Creates and persists a plan from the latest confirmed teaching intent. The request body is empty.

### `GET /api/projects/{projectId}/generation-plans/latest`

Returns the newest plan owned by the project. Returns `404` when no plan exists.

### `PUT /api/projects/{projectId}/generation-plans/{planId}`

Saves teacher edits before confirmation.

```json
{
  "pptOutline": [
    {"order": 1, "title": "Cover", "description": "Course title and audience"}
  ],
  "docOutline": [
    {"order": 1, "title": "Course information", "description": "Basic teaching context"}
  ],
  "interactionPlan": ["Opening diagnostic question", "End-of-class quiz"]
}
```

Confirmed plans are immutable. Attempting to edit one returns `409 Conflict`.

### `POST /api/projects/{projectId}/generation-plans/{planId}/confirm`

Confirms the plan and unlocks artifact generation. Repeating confirmation is idempotent.

## 5. Artifact Generation And Query

### `POST /api/projects/{projectId}/artifacts/generate`

```json
{
  "planId": 31
}
```

The response is the complete artifact list for version `v1`. The Mock implementation is synchronous; a later real provider may expose an asynchronous job without changing artifact schemas.

### `GET /api/projects/{projectId}/artifacts`

Returns artifact summaries and normalized content for the project's current version, ordered by type and creation time.

### `GET /api/projects/{projectId}/artifacts/{artifactId}`

Returns one artifact only when it belongs to the path project. Cross-project access returns `404`.

## 6. Content Schemas

### 6.1 PPT

```json
{
  "deckTitle": "Artificial intelligence foundations",
  "theme": "clean technology",
  "slides": [
    {
      "index": 1,
      "kind": "COVER",
      "title": "Artificial intelligence foundations",
      "layout": "TITLE",
      "points": ["Target: first-year university students"],
      "speakerNotes": "Introduce the course context."
    }
  ]
}
```

The generated deck contains at least seven slides covering `COVER`, `AGENDA`, `OBJECTIVES`, `CONTENT`, `CASE`, `INTERACTION`, and `SUMMARY`.

### 6.2 Lesson plan (`DOCX`)

```json
{
  "title": "Artificial intelligence foundations lesson plan",
  "courseInfo": {},
  "teachingGoals": [],
  "keyPoints": [],
  "difficultPoints": [],
  "methods": [],
  "teachingProcess": [],
  "classroomActivities": [],
  "homework": [],
  "resourceNotes": []
}
```

### 6.3 Interaction

```json
{
  "title": "Knowledge checkpoint",
  "instructions": "Choose one answer for each question.",
  "questions": [
    {
      "id": "q1",
      "question": "Which statement is correct?",
      "options": ["A", "B", "C", "D"],
      "correctOption": 1,
      "explanation": "B matches the concept introduced in the lesson."
    }
  ]
}
```

`correctOption` is zero-based. The frontend must not reveal the answer until the learner submits a choice.

## 7. Error Semantics

| HTTP | Scenario |
| --- | --- |
| `400` | Invalid path ID, empty plan structure, or invalid artifact request |
| `404` | Project, teaching intent, plan, or artifact does not exist in the requested project |
| `409` | Teaching intent not confirmed, confirmed plan is edited, or artifacts are requested before plan confirmation |
| `503` | Selected AI provider is unavailable and no Mock fallback is allowed |

Server-side file paths and provider secrets are never returned to the frontend.

## 8. M4 Compatibility

M4 will add immutable version snapshots, edit records, restore/finalize operations, and real export jobs. It must reuse the `schemaVersion=1` normalized content from this contract rather than re-prompting the AI during export.
