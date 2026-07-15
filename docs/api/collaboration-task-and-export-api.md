# A12 Collaboration Task and Export API

Last updated: 2026-07-14

This contract covers the first real collaboration slice added after M3: identity-scoped projects, course and class reference data, leader-assigned teaching tasks, teacher submission, and real Office export. It is the source of truth for the corresponding Vue pages.

## 1. Authentication

All endpoints in this document require:

```http
Authorization: Bearer <token>
```

The active role comes from the authenticated session. Switching roles uses `POST /api/v1/auth/switch-role`; sending a role field in a business request never changes authorization.

| HTTP | Meaning |
| --- | --- |
| `400` | Invalid request or invalid state transition |
| `401` | Missing, expired, revoked, or invalid token |
| `403` | Active role or data scope is not allowed |
| `404` | Resource does not exist |
| `409` | Unique value or active workflow conflict |

Unless noted otherwise, responses use `ApiResponse<T>`:

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": "2026-07-14T10:00:00Z"
}
```

## 2. Project ownership

Teacher-created projects are assigned an `ownerUserId` on the server. The authenticated teacher can list, open, update, and export only owned projects. A leader may reference a project in a teaching task only when that project belongs to the assigned teacher.

Legacy unowned projects are migrated to the configured demo teacher only when demo seeding is enabled. They are not exposed globally in normal authenticated operation.

Existing project endpoints remain under `/api/projects`; they now enforce the authenticated teacher's ownership scope.

## 3. Course and class data

### 3.1 List courses

```http
GET /api/v1/courses
Roles: LEADER, TEACHER, STUDENT
```

### 3.2 Create course

```http
POST /api/v1/courses
Role: LEADER
Content-Type: application/json
```

```json
{
  "courseCode": "AI-101",
  "courseName": "人工智能基础",
  "description": "面向本科一年级的人工智能导论课程"
}
```

`courseCode` is case-insensitively unique.

### 3.3 List classes

```http
GET /api/v1/classes
GET /api/v1/classes?courseId=1
Roles: LEADER, TEACHER, STUDENT
```

### 3.4 Create class

```http
POST /api/v1/courses/{courseId}/classes
Role: LEADER
```

```json
{
  "className": "计算机科学一班",
  "cohort": "2026级",
  "studentCount": 36
}
```

The class name is unique within a course.

### 3.5 Task assignment reference data

```http
GET /api/v1/collaboration/reference-data
Roles: LEADER, TEACHER
```

```json
{
  "teachers": [
    { "id": 2, "username": "teacher", "displayName": "张老师" }
  ],
  "leaders": [
    { "id": 1, "username": "leader", "displayName": "教研负责人" }
  ],
  "courses": [],
  "classes": []
}
```

Only enabled users are returned. `teachers` contains valid task assignees; `leaders` contains valid approval reviewers.

## 4. Teaching tasks

### 4.1 Status model

```text
ASSIGNED -> IN_PROGRESS -> SUBMITTED
ASSIGNED ----------------> SUBMITTED
SUBMITTED -> REVISION_REQUIRED -> IN_PROGRESS -> SUBMITTED
SUBMITTED -> COMPLETED
non-terminal -> CANCELLED
```

The leader owns task creation and review transitions. The assigned teacher owns progress and submission transitions. `overdue` is computed from `dueAt` and is false for `COMPLETED` or `CANCELLED` tasks.

### 4.2 Create and assign

```http
POST /api/v1/teaching-tasks
Role: LEADER
```

```json
{
  "taskName": "完成人工智能基础教学设计",
  "courseId": 1,
  "classId": 1,
  "chapterTitle": "人工智能核心概念与应用",
  "assigneeId": 2,
  "requirements": "完成需求澄清、资料增强、教学意图和教学资源生成。",
  "priority": "HIGH",
  "dueAt": "2026-07-21T18:00:00",
  "linkedProjectId": null
}
```

`priority`: `LOW`, `MEDIUM`, `HIGH`, `URGENT`.

### 4.3 List tasks

```http
GET /api/v1/teaching-tasks
GET /api/v1/teaching-tasks?status=SUBMITTED
Roles: LEADER, TEACHER
```

- `LEADER`: only tasks created by the current leader.
- `TEACHER`: only tasks assigned to the current teacher.
- `STUDENT`: `403`.

### 4.4 Get or update task

```http
GET /api/v1/teaching-tasks/{taskId}
PUT /api/v1/teaching-tasks/{taskId}
```

Only the creator leader may edit. A submitted, completed, or cancelled task cannot be edited.

### 4.5 Teacher progress

```http
PUT /api/v1/teaching-tasks/{taskId}/status
Role: TEACHER
```

```json
{
  "status": "IN_PROGRESS",
  "note": null
}
```

### 4.6 Teacher submission

```http
POST /api/v1/teaching-tasks/{taskId}/submit
Role: TEACHER
```

```json
{
  "note": "已完成教学方案与资源生成，请审核。",
  "linkedProjectId": 24
}
```

If `linkedProjectId` is supplied, the project must belong to the current teacher.

### 4.7 Leader review of task completion

```http
PUT /api/v1/teaching-tasks/{taskId}/status
Role: LEADER
```

Revision request:

```json
{
  "status": "REVISION_REQUIRED",
  "note": "请增加课堂互动环节，并重新提交。"
}
```

Completion:

```json
{
  "status": "COMPLETED",
  "note": "任务交付符合要求。"
}
```

## 5. Fixed-version approval

Approval always points to an existing `ArtifactVersion` whose `finalVersion` is `true`. Reviewing a request never mutates the historical version or its generated artifacts.

### 5.1 Submit for approval

```http
POST /api/v1/approval-requests
Role: TEACHER
```

```json
{
  "projectId": 24,
  "artifactVersionId": 8,
  "reviewerId": 1
}
```

The project must belong to the teacher, the version must belong to the project, and the reviewer must be an enabled leader. Only one active request may exist for the same artifact version.

### 5.2 List and read requests

```http
GET /api/v1/approval-requests
GET /api/v1/approval-requests?status=SUBMITTED
GET /api/v1/approval-requests/{approvalRequestId}
Roles: TEACHER, LEADER
```

- `TEACHER`: requests submitted by the current teacher.
- `LEADER`: requests assigned to the current leader.
- `STUDENT`: `403`.

Statuses: `SUBMITTED`, `APPROVED`, `REVISION_REQUIRED`, `CANCELLED`.

### 5.3 Review

```http
PUT /api/v1/approval-requests/{approvalRequestId}/review
Role: LEADER
```

Approve:

```json
{ "status": "APPROVED", "note": "内容符合发布要求。" }
```

Return for revision:

```json
{ "status": "REVISION_REQUIRED", "note": "请补充课堂互动和评价标准。" }
```

A revision note is required. A request can only be reviewed once.

### 5.4 Cancel

```http
POST /api/v1/approval-requests/{approvalRequestId}/cancel
Role: TEACHER
```

Only the submitter may cancel a request while it is still `SUBMITTED`.

## 6. Real Office export

Export endpoints return generated binary files, not text files with renamed extensions.

### 6.1 Available formats

```http
GET /api/v1/projects/{projectId}/exports
Role: TEACHER
```

The teacher must own the project or be the assignee of a teaching task linked to the project. The response lists only formats backed by existing generated artifacts.

### 6.2 Download PPTX

```http
GET /api/v1/projects/{projectId}/exports/pptx
Role: TEACHER
Response: application/vnd.openxmlformats-officedocument.presentationml.presentation
```

### 6.3 Download DOCX

```http
GET /api/v1/projects/{projectId}/exports/docx
Role: TEACHER
Response: application/vnd.openxmlformats-officedocument.wordprocessingml.document
```

Successful downloads create an `ExportRecord`. Unsupported formats return `400`; missing generated artifacts return `404`.

## 7. Frontend routes

| Route | Role | API |
| --- | --- | --- |
| `/leader/courses` | LEADER | courses, classes, reference data |
| `/leader/tasks` | LEADER | task list/create/update/review |
| `/leader/approvals` | LEADER | incoming fixed-version approvals |
| `/tasks` | TEACHER | assigned task list/progress/submit |
| `/approvals` | TEACHER | own approval submissions |
| `/projects/:projectId/export` | TEACHER | export catalog and binary download |

Sidebar entries must exist only for implemented routes. Inactive entries remain neutral; hover and current-route states use the purple highlight. Unimplemented modules must be omitted rather than linked to `/projects` or shown as active.

## 8. Verification

Backend focused tests:

```powershell
cd backend
D:\pri_work\.tools\apache-maven-3.9.9\bin\mvn.cmd -B -ntp `
  -Dtest=TeachingTaskSecurityIntegrationTest,ProjectOwnershipSecurityIntegrationTest,ArtifactExportControllerTest test
```

Frontend:

```powershell
cd frontend
npm.cmd run build
```
