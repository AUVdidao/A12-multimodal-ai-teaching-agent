# TA-006 Project Flow Contract

This document records the MVP entry flow completed by TA-006.

## Frontend Flow

1. `/` home page
2. `/projects` project list
3. `/projects/new` create teaching project
4. `/projects/:projectId/mode` choose generation mode
5. `/requirements?projectId=:projectId` enter teaching requirement input

The flow only prepares the teacher-side entry path. Requirement clarification, material upload, RAG, Dify integration, artifact generation, and export remain separate follow-up tasks.

## Project APIs

All endpoints use the existing `ApiResponse` wrapper.

### POST `/api/projects`

Creates a teaching project.

Request:

```json
{
  "courseName": "数学",
  "chapterTitle": "分数的意义",
  "targetStudents": "小学五年级",
  "lessonDuration": 40,
  "description": "用于课堂导入与互动练习"
}
```

Response data:

```json
{
  "id": 1,
  "projectName": "数学 - 分数的意义",
  "courseName": "数学",
  "chapterTitle": "分数的意义",
  "targetStudents": "小学五年级",
  "lessonDuration": 40,
  "description": "用于课堂导入与互动练习",
  "modelMode": "STANDARD",
  "status": "CREATED",
  "createdAt": "2026-07-09T00:00:00",
  "updatedAt": "2026-07-09T00:00:00"
}
```

### GET `/api/projects`

Returns projects ordered by latest update time.

### GET `/api/projects/{projectId}`

Returns one project by id.

### PUT `/api/projects/{projectId}`

Updates base project fields. It does not change workflow status or generation mode.

## Model Mode APIs

### GET `/api/model-modes`

Returns three supported modes:

- `STANDARD`: 标准模式，平衡质量和速度。
- `QUALITY`: 高质量模式，生成更细致但耗时更长。
- `ECONOMY`: 经济模式，速度快、成本低，适合快速草稿。

### PUT `/api/projects/{projectId}/model-mode`

Saves the selected mode for one project.

Request:

```json
{
  "mode": "QUALITY"
}
```

### GET `/api/projects/{projectId}/model-mode`

Returns the current selected mode for one project.

## Validation

Backend:

```bash
cd backend
mvn test
```

Frontend:

```bash
cd frontend
npm run build
```

Manual flow:

1. Start backend on `http://localhost:8080`.
2. Start frontend on `http://localhost:5173`.
3. Visit `/projects`, create a project, choose a mode, and confirm navigation to `/requirements?projectId=...`.
