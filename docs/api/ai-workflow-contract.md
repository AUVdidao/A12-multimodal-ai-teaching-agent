# TA-005 AI Workflow Contract

This document records the backend AI workflow abstraction added for A12 / TA-005. It is intentionally Mock-first: the MVP can run without a real Dify key, and later Dify integration should replace the gateway implementation without changing business callers.

## Configuration

```yaml
a12:
  ai:
    provider: MOCK
    fallback-to-mock: true
    dify:
      base-url: https://api.dify.ai/v1
      workflow-id:
      api-key:
```

Rules:

- `provider=MOCK` is the default and requires no external service.
- `provider=DIFY` is only a switch placeholder in TA-005.
- Keep `fallback-to-mock=true` for demos unless a real Dify workflow is implemented and verified.
- Do not commit real Dify keys, API keys, database passwords, or server passwords.

## API Surface

Base path: `/api/ai-workflow`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/status` | Returns requested provider, active provider, Dify config state, and fallback state. |
| `POST` | `/clarification` | Detects missing teaching requirement fields and returns follow-up questions. |
| `POST` | `/requirement-summary` | Converts raw requirement and dialog turns into structured teaching requirement summary. |
| `POST` | `/material-analysis` | Produces Mock material summary, keywords, teaching uses, and chunk suggestions. |
| `POST` | `/knowledge-retrieval` | Returns Mock knowledge snippets and retrieval notes. |
| `POST` | `/teaching-intent` | Combines requirement summary and knowledge snippets into generation intent. |
| `POST` | `/generation-plan` | Returns PPT outline, lesson-plan outline, and interaction plan. |
| `POST` | `/revision` | Applies a Mock natural-language revision and returns a new version suggestion. |

All endpoints return the existing unified response shape:

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": "..."
}
```

## Example: Clarification

Request:

```json
{
  "projectId": 1,
  "rawRequirement": "帮我做一节数学分数课",
  "knownFields": ["courseName", "chapterTopic"],
  "generationMode": "MOCK"
}
```

Response data:

```json
{
  "workflow": "mock-ai-workflow",
  "missingFields": ["targetAudience", "lessonDurationMinutes", "outputTypes"],
  "questions": [
    "这节课面向哪个年级或学段的学生？",
    "这节课预计多少分钟或几个课时？",
    "需要生成 PPT、Word 教案、互动内容中的哪些产物？"
  ],
  "suggestedFields": {
    "targetAudience": "小学五年级",
    "lessonDurationMinutes": "40",
    "outputTypes": "PPT, DOCX, INTERACTION"
  },
  "nextAction": "请先补充缺失字段，再生成需求摘要。"
}
```

## Example: Generation Plan

Request:

```json
{
  "projectId": 1,
  "courseName": "数学",
  "chapterTopic": "分数的意义",
  "targetAudience": "小学五年级",
  "outputTypes": ["PPT", "DOCX", "INTERACTION"],
  "generationMode": "STANDARD"
}
```

Response data includes:

- `planId`
- `pptOutline`
- `docOutline`
- `interactionPlan`
- `estimatedDuration`
- `nextAction`

## Backend Integration Rule

Business modules should depend on `AIWorkflowGateway`, not on Dify HTTP details. In TA-005, `AIWorkflowGatewayRouter` delegates to `MockAIWorkflowGateway`. A future Dify implementation should preserve the same request and response records.
