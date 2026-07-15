# A12 协作教学平台 API 文档

> C5 前端重构联调版。本文只按当前后端控制器、DTO、服务和集成测试整理；没有把规划中的接口写成已实现接口。路径均为后端实际路径，前端再按部署环境拼接 host。

## 0. 通用约定

### 0.1 认证与响应包装

- 默认 `a12.security.enabled=true`。除注册、登录、健康检查外，前端应发送 `Authorization: Bearer <token>`。
- 成功的 JSON 接口统一返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": "2026-07-15T12:00:00Z"
}
```

- `data` 可能是对象、数组、`null`；分页只在明确标注的工作台接口中出现。
- 业务时间字段来自 `LocalDateTime`，通常没有时区；顶层 `timestamp` 是 ISO-8601 `Instant`。
- 二进制下载接口不使用 `ApiResponse`，前端按 `Content-Type`、`Content-Disposition` 和 Blob 处理。

### 0.2 错误状态

| HTTP | `code` | 当前含义 |
|---|---:|---|
| 400 | 400 | 参数校验、枚举值、状态流转、请求体或业务前置条件错误 |
| 401 | 401 | 缺少、失效或错误 Bearer token；登录凭证错误 |
| 403 | 403 | activeRole 不允许、不是资源负责人/owner、无班级或审批范围权限 |
| 404 | 404 | 资源不存在，或路径资源不属于当前 project |
| 409 | 409 | 重复创建、状态冲突、前置流程未完成、并发处理中 |
| 413 | 413 | 上传文件超过配置大小 |
| 500 | 500 | 未分类服务端异常 |
| 503 | 503 | AI workflow 不可用，见第 4 节的 Mock/Dify 说明 |

错误 JSON 仍是 `{ code, message, data: null, timestamp }`。字段校验错误的 `message` 可能是多个 `field: reason` 用 `; ` 拼接。

### 0.3 activeRole 与资源范围

- 账号可拥有多个 `roles`，但每个 token 只有一个 `activeRole`；后端按 activeRole 授权，不按前端页面判断。
- `TEACHER` 通常只能访问自己 owner 的 project；project 级资料、需求、摘要、意图、生成、版本、导出都走 owner 校验。
- `LEADER` 负责课程/班级配置、派发/审核任务、审批和发布；不能冒充教师修改教师 project。
- `STUDENT` 读取课程/班级和已发布学习任务，并在已加入班级的学习任务下提问。
- 多角色账号可以调用切换角色接口；切换不发行新 token，但会改变当前 token 的 activeRole。

### 0.4 角色矩阵

| 能力 | TEACHER | LEADER | STUDENT |
|---|---|---|---|
| 课程/班级维护 | 只读课程/班级 | 创建课程、创建班级 | 只读 |
| 成员关系维护 | 无 | 查询、添加、移除成员 | 无维护权限 |
| 教学任务 | 被指派 teacher 查询、提交和推进 | 创建、分配、审核状态、取消 | 无 |
| 项目、需求、资料、生成、版本、修订、导出 | 仅项目 owner | 无项目 owner 修改权；导出按源码任务授权规则 | 无 |
| 审批 | 提交自己项目的定稿版本、撤回 | 被分配 reviewer 才能审核 | 无 |
| 发布 | 读取自己 owner 项目的发布记录 | 发布、读取自己的发布记录、撤回 | 只读已加入班级的学习任务 |
| 问答 | owner teacher 回答自己项目问题；可管理自己范围问题 | 读取自己发布范围问题 | 创建自己的问题、读取自己的问题、按服务端状态规则更新 |

矩阵只描述当前 Controller/Service 的有效边界；前端不能用页面可见性代替服务端 `activeRole`、owner、reviewer、assignee 或 class membership 校验。

### 0.5 最小请求/响应示例

登录成功：

```json
POST /api/v1/auth/login
{ "username": "teacher", "password": "<demo-password>", "activeRole": "TEACHER" }

{ "code": 0, "message": "success", "data": {
  "token": "<bearer-token>", "expiresAt": "2026-07-15T18:00:00",
  "user": { "id": 2, "username": "teacher", "displayName": "...", "roles": ["TEACHER"], "activeRole": "TEACHER" }
}, "timestamp": "2026-07-15T12:00:00Z" }
```

成果修订成功：

```json
POST /api/v1/projects/42/artifacts/101/revisions
{ "instruction": "补充一个课堂小结" }

{ "code": 0, "message": "success", "data": {
  "version": { "id": 12, "projectId": 42, "generationPlanId": 7,
    "versionNumber": 3, "description": "...", "finalVersion": false,
    "artifactCount": 3, "createdAt": "2026-07-15T12:00:00" },
  "artifacts": [{ "id": 201, "versionId": 12, "versionNumber": 3,
    "type": "PPT", "schemaVersion": 1, "content": { "slides": [] } }],
  "changeSummary": "...", "changedSections": ["课堂互动"],
  "requestedProvider": "MOCK", "activeProvider": "MOCK", "mockProvider": true,
  "providerMessage": "Mock AI workflow is active...",
  "editRecord": { "id": 8, "projectId": 42, "versionId": 12,
    "instruction": "补充一个课堂小结", "resultSummary": "...", "createdAt": "..." }
}, "timestamp": "..." }
```

## 1. 登录、当前用户与 RBAC

### 角色权限

注册公开；登录返回 token。注册账号固定为 `STUDENT`，请求体中的额外角色字段不会提升权限。所有业务权限以服务端的 activeRole、owner、reviewer、assignee、class membership 为准。

### 接口

| 方法与路径 | 权限 | 请求体 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|---|
| `POST /api/v1/auth/register` | 公开 | `{ username, displayName, password }`；用户名 3-50 位且只允许字母/数字/`.`/`_`/`-`，密码 8-72 位 | `{ token, expiresAt, user: { id, username, displayName, roles: ["STUDENT"], activeRole: "STUDENT" } }` | `400` 校验失败，`409` 用户名已注册 |
| `POST /api/v1/auth/login` | 公开 | `{ username, password, activeRole? }` | 同注册，但 roles 来自账号；`activeRole` 是本次会话角色 | `400` 校验失败，`401` 用户名/密码错误，`403` 无角色或请求角色未分配 |
| `GET /api/v1/auth/me` | 已认证 | 无 | `{ id, username, displayName, roles, activeRole }` | `401` |
| `POST /api/v1/auth/switch-role` | 已认证 | `{ role: "TEACHER" | "LEADER" | "STUDENT" }` | 当前用户 profile；同一个 token 后续按新 activeRole 授权 | `400`/`403` role 缺失或未分配，`401` |
| `POST /api/v1/auth/logout` | 已认证 | 无 | `data: null` | `401` |

### 演示账号

仅当 `a12.security.demo-seed-enabled=true` 且对应密码环境变量非空时，启动种子账号：

| username | role | 密码来源 |
|---|---|---|
| `leader` | `LEADER` | `A12_DEMO_LEADER_PASSWORD` |
| `teacher` | `TEACHER` | `A12_DEMO_TEACHER_PASSWORD` |
| `student` | `STUDENT` | `A12_DEMO_STUDENT_PASSWORD` |
| `multi` | `TEACHER`, `LEADER` | `A12_DEMO_MULTI_PASSWORD` |

源码没有提供固定演示密码，前端/测试不得硬编码密码。

### 前端联调注意事项

- 登录成功后保存 `data.token`、`data.expiresAt` 和 `data.user`；请求只放 Bearer token，不把 token 放 query string。
- 角色切换成功后立即用返回的 `data.activeRole` 更新权限视图，并刷新依赖角色的数据。
- token 被撤销后 `/api/v1/auth/me` 返回 `401`，应清理本地会话并回登录页。

## 2. 课程、班级、成员关系、教学任务与项目

### 2.1 课程、班级和协作引用数据

| 方法与路径 | 角色 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|---|
| `POST /api/v1/courses` | `LEADER` | `{ courseCode, courseName, description? }` | `{ id, courseCode, courseName, description, createdBy, createdAt, updatedAt }` | `400` 校验，`409` courseCode 重复 |
| `GET /api/v1/courses` | `LEADER`/`TEACHER`/`STUDENT` | 无 | `CourseResponse[]` | `401`/`403` |
| `POST /api/v1/courses/{courseId}/classes` | `LEADER` | `{ className, cohort?, studentCount? }` | `{ id, courseId, courseName, className, cohort, studentCount, createdAt, updatedAt }` | `400` 校验，`404` course 不存在，`409` 同课程班级重名 |
| `GET /api/v1/classes?courseId={id}` | `LEADER`/`TEACHER`/`STUDENT` | `courseId` 可省略 | `ClassGroupResponse[]`；省略时返回全部班级 | `400` 参数类型，`401`/`403` |
| `GET /api/v1/collaboration/reference-data` | `LEADER`/`TEACHER` | 无 | `{ teachers, leaders, students, courses, classes }`；用户项为 `{ id, username, displayName }` | `401`/`403` |

请求约束：`courseCode` 最长 40，`courseName` 最长 120，`className` 最长 120，`cohort` 最长 80，`description` 最长 500，`studentCount >= 0`。

### 2.2 成员关系

成员关系由课程 Controller 提供真实 HTTP 接口，写操作仅 `LEADER`，目标 student 必须是已存在的 `STUDENT`：

| 方法与路径 | 角色 | 请求体 | 成功 `data` | 主要 4xx |
|---|---|---|---|---|
| `GET /api/v1/classes/{classId}/members` | `LEADER` | 无 | `ClassMembershipResponse[]`：`id/classId/studentId/username/displayName/createdAt` | `403` 非 leader，`404` 班级不存在 |
| `POST /api/v1/classes/{classId}/members` | `LEADER` | `{ "studentId": 123 }` | 新增的 `ClassMembershipResponse` | `400` studentId 非正数，`403`，`404` 班级或 student，`409` 已是成员 |
| `DELETE /api/v1/classes/{classId}/members/{studentId}` | `LEADER` | 无 | `data: null` | `403`，`404` 班级/成员不存在 |

`GET /api/v1/collaboration/reference-data` 的真实返回包含 `teachers`、`leaders`、`students`、`courses`、`classes`，可用于教学任务、审批 reviewer、项目协作下拉。不要把 membership 当作课程创建请求字段，也不要从 `studentCount` 推断具体成员。

### 2.3 教学任务

接口前缀：`/api/v1/teaching-tasks`。

| 方法与路径 | 角色 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|---|
| `POST /api/v1/teaching-tasks` | `LEADER` | `{ taskName, courseId, classId?, chapterTitle, assigneeId, requirements, priority, dueAt, linkedProjectId? }` | `TeachingTaskResponse`，重点是 `id/taskStatus/assigneeId/linkedProjectId/dueAt/overdue` | `400` 引用、校验或 linked project owner 错误，`404` course/class/user/project，`403` |
| `GET /api/v1/teaching-tasks?status={status}` | `LEADER`/`TEACHER` | status 可省略 | leader 看到自己创建的，teacher 看到自己被指派的 | `400` 非法 status，`403` |
| `GET /api/v1/teaching-tasks/{taskId}` | 创建 leader 或被指派 teacher | 无 | 完整 `TeachingTaskResponse`，含 `submissionNote/reviewNote/submittedAt/completedAt` | `403` 非本人范围，`404` |
| `PUT /api/v1/teaching-tasks/{taskId}` | 创建该任务的 `LEADER` | 同创建体 | 更新后的任务；`REVISION_REQUIRED` 编辑后回到 `ASSIGNED` | `400` 已提交/已关闭不可编辑、校验，`403`，`404` |
| `PUT /api/v1/teaching-tasks/{taskId}/status` | leader 或被指派 teacher | `{ status, note? }` | 更新后的 `taskStatus`、审核/完成时间和 note | `400` 非法流转/缺退回说明，`403`，`404` |
| `POST /api/v1/teaching-tasks/{taskId}/submit` | 被指派 `TEACHER` | `{ note, linkedProjectId? }` | `taskStatus: "SUBMITTED"`、`submissionNote`、`submittedAt` | `400` 非法流转，`403` 非本人或项目 owner，`404` |

状态枚举：`DRAFT`、`ASSIGNED`、`IN_PROGRESS`、`SUBMITTED`、`REVISION_REQUIRED`、`COMPLETED`、`CANCELLED`。创建实际落为 `ASSIGNED`；teacher 只能 `ASSIGNED/REVISION_REQUIRED -> IN_PROGRESS`，再提交为 `SUBMITTED`；leader 可对 submitted 任务改为 `COMPLETED` 或 `REVISION_REQUIRED`，也可取消未终态任务。

优先级枚举：`LOW`、`MEDIUM`、`HIGH`、`URGENT`。`dueAt` 必须是未来时间。

### 2.4 项目与模型模式

项目接口前缀：`/api/projects`，当前实现要求已登录教师且 project owner 匹配；项目列表只返回当前用户 owner 的项目。

| 方法与路径 | 角色 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|---|
| `POST /api/projects` | `TEACHER` | `{ projectName?, courseName, chapterTitle, targetStudents?, lessonDuration?, description? }` | `{ id, projectName, courseName, chapterTitle, targetStudents, lessonDuration, description, modelMode, status, createdAt, updatedAt, deletedAt }` | `400` course/chapter 缺失或 lessonDuration 非正数 |
| `GET /api/projects` | `TEACHER` | 无 | `ProjectResponse[]` | `401`/`403` |
| `GET /api/projects/{projectId}` | owner `TEACHER` | 无 | `ProjectResponse` | `403` 跨教师，`404` |
| `PUT /api/projects/{projectId}` | owner `TEACHER` | 同创建体 | 更新后的 `ProjectResponse` | `400`、`403`、`404` |
| `GET /api/model-modes` | 需按当前认证链路调用；实际服务返回固定选项 | 无 | `[{ code, name, description }]`，实际 code 为 `STANDARD`/`QUALITY`/`ECONOMY` | `401`/`403` |
| `GET /api/projects/{projectId}/model-mode` | owner `TEACHER` | 无 | `{ projectId, mode, name, description }` | `403`/`404` |
| `PUT /api/projects/{projectId}/model-mode` | owner `TEACHER` | `{ mode: "STANDARD" | "QUALITY" | "ECONOMY" }` | 同上 | `400` 不支持模式，`403`/`404` |

项目状态枚举：`CREATED`、`REQUIREMENT_CONFIRMED`、`MATERIAL_READY`、`INTENT_CONFIRMED`、`GENERATED`、`FINALIZED`。模式在响应中规范化为 `STANDARD`、`QUALITY`、`ECONOMY`；源码内部的 `HIGH_QUALITY` 会返回 `QUALITY`。

### 2.5 最近访问、软删除与回收站

以下四个接口均属于 `/api/projects`，只允许项目 owner 的 activeRole=`TEACHER` 操作或读取；跨 owner 返回 `403`。删除是软删除：服务端只写入 `Project.deletedAt`，不删除项目及其关联数据。

| 方法与路径 | 权限 | 请求体 | 成功 `data` 关键字段 | 主要 4xx/语义 |
|---|---|---|---|---|
| `GET /api/projects/recent` | owner `TEACHER` | 无 | `RecentProjectResponse[]`：`project: ProjectResponse`、`lastVisitedAt`、`visitCount`；按最近访问倒序，最多返回最近 20 条访问记录 | `401` 未认证，`403` 非 teacher；已软删除或非本人 owner 的访问记录不返回 |
| `GET /api/projects/recycle-bin` | owner `TEACHER` | 无 | `ProjectResponse[]`；仅当前教师自己的 `deletedAt != null` 项目，按删除时间倒序 | `401` 未认证，`403` 非 teacher |
| `DELETE /api/projects/{projectId}` | 项目 owner `TEACHER` | 无 | `data: null` | `403` 跨 owner，`404` 项目不存在；成功后普通 `GET /api/projects`、详情访问和 `/recent` 均不可见 |
| `POST /api/projects/{projectId}/restore` | 被删除项目的 owner `TEACHER` | 无 | 恢复后的 `ProjectResponse`，`deletedAt` 不存在/为 `null` | `403` 跨 owner，`404` 项目不存在，`400` 项目不在回收站（重复恢复） |

软删除项目仍可在回收站中看到 `deletedAt`，恢复后重新出现在普通项目列表；最近访问记录本身不会绕过删除状态过滤。前端应以 `deletedAt` 判断回收站展示，不要把删除当作物理删除，也不要把 `RecentProjectResponse` 当作普通 `ProjectResponse` 直接解构。

## 3. 需求、澄清与摘要

这些 `/api/projects/{projectId}/...` 接口的角色是 owner `TEACHER`；跨教师 project 返回 `403`，不存在 project 返回 `404`。

### 3.1 需求录入

| 方法与路径 | 请求体 | 成功 `data` 关键字段 | 主要 4xx/前置条件 |
|---|---|---|---|
| `POST /api/projects/{projectId}/requirements` | `gradeLevel?`, `subject?`, `topic?`, `baselineLevel?`, `lessonDuration?`, `teachingGoals?`, `keyPoints?`, `difficultPoints?`, `stylePreference?`, `interactionType?`, `outputTypes?`, `rawRequirementText?` | `RequirementInputResponse`：`id/projectId`、各需求字段、`outputTypes`、`createdAt/updatedAt` | `400` 字段超长；`topic` 与 `rawRequirementText` 不能同时为空，`403`/`404` |
| `GET /api/projects/{projectId}/requirements/latest` | 无 | 最新需求对象；没有记录时 `data: null` | `400` projectId 非正数，`403`/`404` |

`outputTypes` 最多 10 项，每项最多 50 字符；`rawRequirementText` 最多 10000 字符。

### 3.2 澄清检查与问题

| 方法与路径 | 角色 | 请求体 | 成功 `data` | 主要 4xx |
|---|---|---|---|---|
| `POST /api/projects/{projectId}/clarification/check` | owner `TEACHER` | `{ gradeLevel?, subject?, topic?, lessonDuration?, teachingGoals?, keyPoints?, difficultPoints?, outputTypes?, rawRequirementText? }` | `{ complete, missingFields: [{ field, label, reason }], questions: [] }` | `400` rawRequirementText 超长或 projectId 非法，`403`/`404` |
| `POST /api/projects/{projectId}/clarification/questions` | owner `TEACHER` | 同上 | `{ complete, missingFields, questions }`；问题由当前 AI gateway 生成/适配 | 同上，另有 AI 不可用时 `503` |

澄清字段当前实际检查：`gradeLevel`、`subject`、`topic`、`lessonDuration`、`teachingGoals`、`outputTypes`。前端以返回的 `missingFields` 为准，不要硬编码缺失字段顺序。

### 3.3 需求摘要

| 方法与路径 | 请求体 | 成功 `data` 关键字段 | 主要 4xx/状态 |
|---|---|---|---|
| `POST /api/projects/{projectId}/requirement-summaries/generate` | 无 | `RequirementSummaryResponse`：`id/sourceRequirementId`、需求字段、`generationMode`、`status` | `409` 没有需求；`403`/`404` |
| `GET /api/projects/{projectId}/requirement-summaries/latest` | 无 | 最新摘要；没有记录时 `null` | `403`/`404` |
| `PUT /api/projects/{projectId}/requirement-summaries/{summaryId}` | 需求摘要字段：`gradeLevel?`, `subject?`, `topic?`, `baselineLevel?`, `lessonDuration?`, `teachingGoals?`, `keyPoints?`, `difficultPoints?`, `outputTypes?`, `stylePreference?`, `interactionType?` | 更新后的摘要 | `400` 校验/缺体，`409` 已确认摘要不可修改，`403`/`404` |
| `POST /api/projects/{projectId}/requirement-summaries/{summaryId}/confirm` | 无 | `status: "CONFIRMED"`、`confirmedAt`；项目状态变为 `REQUIREMENT_CONFIRMED` | `400` 摘要不完整，`403`/`404` |

摘要状态：`DRAFT`、`CONFIRMED`。确认所需的当前字段为年级、学科、主题、课时、教学目标和至少一个 output type。

### 前端联调注意事项

- `generate` 是幂等式复用当前需求对应的摘要，不要把生成按钮当成无限创建。
- 确认后编辑控件应锁定；服务端仍会以 `409` 拒绝修改。
- 澄清请求的 `projectId` 在路径，不在 JSON body；AI workflow DTO 的同名接口才把 `projectId` 放 body，不能混用。

## 4. 资料、解析、知识检索与教学意图

### 4.1 资料与用途标注

角色均为 owner `TEACHER`。接口前缀：`/api/projects/{projectId}/materials`。

| 方法与路径 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|
| `POST /api/projects/{projectId}/materials` | `multipart/form-data`：必填 `file`，可选 query/form 参数 `description`（最多 300） | `MaterialResponse`：`id/projectId/originalFilename/fileType/fileSize/uploadStatus/parseStatus/usageTypes/downloadPath` | `400` 缺文件/文件名/类型，`413` 超过 200MB，`403`/`404` |
| `GET /api/projects/{projectId}/materials` | 无 | `MaterialResponse[]` | `403`/`404` |
| `GET /api/projects/{projectId}/materials/{materialId}` | 无 | 单个 `MaterialResponse` | `403`/`404` |
| `GET /api/projects/{projectId}/materials/{materialId}/download` | 无 | 二进制文件，带 UTF-8 `Content-Disposition` | `403`/`404`/`500` |
| `PUT /api/projects/{projectId}/materials/{materialId}/usages` | `{ usageTypes: PurposeType[], note? }`；usageTypes 非空，note 最长 500 | `MaterialUsageResponse`：`materialId/projectId/usageTypes/note/updatedAt` | `400` 空用途/非法枚举，`403`/`404` |
| `GET /api/projects/{projectId}/materials/{materialId}/usages` | 无 | 同 `MaterialUsageResponse` | `403`/`404` |

`MaterialFileType`：`PDF`、`DOCX`、`PPTX`、`XLSX`、`TXT`、`MD`、`MP4`、`PNG`、`JPG`、`JPEG`、`WORD`、`PPT`、`IMAGE`、`VIDEO`、`OTHER`。上传状态：`UPLOADED`、`PARSED`、`CONFIRMED`、`FAILED`。解析状态：`NOT_STARTED`、`PROCESSING`、`SUCCEEDED`、`FAILED`。用途枚举：`TEXTBOOK_BASIS`、`CASE_MATERIAL`、`EXERCISE_SOURCE`、`KNOWLEDGE_SUPPLEMENT`、`KNOWLEDGE_POINT`、`CASE_REFERENCE`、`CHAPTER_STRUCTURE`、`STYLE_REFERENCE`、`IMAGE_ASSET`、`VIDEO_CONTENT`。

### 4.2 解析和索引

| 方法与路径 | 角色/前置条件 | 成功 `data` | 主要 4xx |
|---|---|---|---|
| `POST /api/projects/{projectId}/materials/{materialId}/parse` | owner teacher；已有确认摘要，且至少一个用途 | `ParseResultResponse`：`parseStatus/summary/keywords/applicableTeachingStages/failureReason/parsedAt/prototype` | `409` 无确认摘要、无用途、处理中；`403`/`404` |
| `GET /api/projects/{projectId}/materials/{materialId}/parse-result` | owner teacher | `ParseResultResponse`；无记录返回 `NOT_STARTED` 和 `prototype: true` | `403`/`404` |
| `POST /api/projects/{projectId}/materials/{materialId}/parse/retry` | owner teacher；已有失败结果 | 同上 | `409` 无失败结果、成功结果或当前非 FAILED；`403`/`404` |
| `POST /api/projects/{projectId}/materials/{materialId}/index` | owner teacher；材料存在 | `KnowledgeChunkResponse[]` | `403`/`404` |

解析成功会把材料变为 `uploadStatus: PARSED`、`parseStatus: SUCCEEDED`，并触发知识索引。解析失败通常返回 200 的 `ParseResultResponse`，其中 `parseStatus: FAILED`；前端不能只看 HTTP 状态。

### 4.3 知识检索

角色为 owner `TEACHER`，前缀 `/api/projects/{projectId}/knowledge`。

| 方法与路径 | 请求体 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|
| `GET /api/projects/{projectId}/knowledge/overview` | 无 | `{ indexedMaterialCount, chunkCount, chunks, prototype }` | `403`/`404` |
| `POST /api/projects/{projectId}/knowledge/search` | `{ query, limit? }`；query 非空，limit 1-20，默认 10 | `{ query, hits: [{ chunkId, materialId, sourceFilename, title, content, score, hitReason, usageTypes, keywords }], prototype, algorithm }` | `400` query 为空或 limit 越界，`403`/`404` |

这是当前本地关键词/评分检索，响应 `prototype: true`；不要把它标记为向量库或 Dify 检索结果。

### 4.4 教学意图

角色为 owner `TEACHER`，前缀 `/api/projects/{projectId}/teaching-intents`。

| 方法与路径 | 请求体 | 成功 `data` 关键字段 | 主要 4xx/前置条件 |
|---|---|---|---|
| `POST /api/projects/{projectId}/teaching-intents/generate` | 无 | `TeachingIntentResponse`：`id/requirementSummaryId/generationGoal/contentBasis/teachingApproach/interactionMode/outputTypes/evidenceItems/status/prototype` | `409` 没有确认摘要、上传材料、成功解析材料、知识块或检索命中；`403`/`404` |
| `GET /api/projects/{projectId}/teaching-intents/latest` | 无 | 最新意图或 `null` | `403`/`404` |
| `PUT /api/projects/{projectId}/teaching-intents/{intentId}` | `{ generationGoal, contentBasis, teachingApproach, interactionMode, outputTypes, stylePreference? }` | 更新后的意图 | `400` 缺字段/空 outputTypes，`409` 已确认不可改，`403`/`404` |
| `POST /api/projects/{projectId}/teaching-intents/{intentId}/confirm` | 无 | `status: "CONFIRMED"`、`confirmedAt`；项目状态变为 `INTENT_CONFIRMED` | `400` 意图不完整，`403`/`404` |
| `POST /api/projects/{projectId}/teaching-intents/{intentId}/revisions` | 无 | `TeachingIntentResponse`；确认版本克隆为新的 `DRAFT`，包含原业务字段和 `evidenceItems` | `403` 非项目 owner 教师，`404` 项目/意图不匹配或项目已软删除 |

意图状态：`DRAFT`、`CONFIRMED`。`evidenceItems` 是当前知识命中证据；前端展示时保留 `sourceFilename/hitReason/contentExcerpt`。
对 `DRAFT` 调用修订接口幂等返回原草稿；原 `CONFIRMED` 版本保持不变，既有 PUT 仍以 `409` 拒绝修改。

### 4.5 AI workflow 与 Dify 事实边界

源码存在 `/api/ai-workflow/status`、`/clarification`、`/requirement-summary`、`/material-analysis`、`/knowledge-retrieval`、`/teaching-intent`、`/generation-plan`、`/revision`，请求体 DTO 在 `ai.dto.AiWorkflowDtos` 中定义。这些接口经过 `AIWorkflowGatewayRouter`，当前默认 `app.ai.provider=MOCK`。

- `GET /api/ai-workflow/status` 返回 `requestedProvider/activeProvider/mockEnabled/difyConfigured/fallbackToMock/message`。
- 当前真实 Dify workflow 尚未实现；源码明确表示 Dify 选择且关闭 Mock fallback 时返回 `503`。
- Dify 配置项只有 `base-url/workflow-id/api-key`，不能据此宣称已完成 Dify 联调。
- 前端应按 `status` 的 `activeProvider` 和 `mockEnabled` 展示能力；不要把 `workflow: "mock-ai-workflow"` 当作真实 Dify 结果。

这些接口受 `/api/**` 的 `TEACHER` 认证规则保护；带 `projectId` 的请求还必须是该项目 owner，项目软删除后按 `404` 处理。所有成功响应仍包在 `ApiResponse.data` 中：

| 方法与路径 | 请求体 | 成功 `data` 关键字段 | 主要 4xx/5xx |
|---|---|---|---|
| `GET /api/ai-workflow/status` | 无 | `requestedProvider/activeProvider/mockEnabled/difyConfigured/fallbackToMock/message` | `401`/`403` |
| `POST /api/ai-workflow/clarification` | `{ projectId, rawRequirement, knownFields?, generationMode?, requestedMissingFields? }` | `workflow/missingFields/questions/suggestedFields/nextAction` | `400` 校验失败，`403`/`404` 项目门禁，`503` provider 不可用 |
| `POST /api/ai-workflow/requirement-summary` | `{ projectId, rawRequirement, dialogTurns?: [{ role, content }], generationMode? }` | `workflow/summary/assumptions/confirmationQuestion`；`summary` 含课程、章节、受众、时长、目标、难点、产物等字段 | `400`，`403`/`404`，`503` |
| `POST /api/ai-workflow/material-analysis` | `{ projectId, fileName, materialType, purpose? }` | `workflow/status/summary/keywords/teachingUses/suggestedChunks` | `400`，`403`/`404`，`503` |
| `POST /api/ai-workflow/knowledge-retrieval` | `{ projectId, courseName, chapterTopic, keywords? }` | `workflow/snippets/retrievalNote`；snippet 含 `title/sourceName/content/score` | `400`，`403`/`404`，`503` |
| `POST /api/ai-workflow/teaching-intent` | `{ projectId, requirementSummary?, knowledgeSnippets? }` | `workflow/intentId/generationGoals/contentBasis/interactionIdeas/outputTypes/confirmationPrompt` | `400`，`403`/`404`，`503` |
| `POST /api/ai-workflow/generation-plan` | `{ projectId, courseName, chapterTopic, targetAudience?, outputTypes?, generationMode? }` | `workflow/planId/pptOutline/docOutline/interactionPlan/estimatedDuration/nextAction` | `400`，`403`/`404`，`503` |
| `POST /api/ai-workflow/revision` | `{ projectId, artifactId, instruction, currentContent }` | `workflow/changeSummary/changedSections/revisedContent/versionSuggestion` | `400`，`403`/`404`，`503` |

`generationMode` 使用 `STANDARD`、`QUALITY`、`HIGH_QUALITY`、`ECONOMY`、`MOCK`；以上 workflow response 的 `workflow`、`status` 和 provider 字段是当前网关结果，不是持久化成果状态。前端必须以 `/status` 的 `activeProvider/mockEnabled` 标识 Mock，不能将 Mock response 伪装为真实模型或 Dify。

### 4.6 容器内部 parser/generator 契约

这是服务间/后端内部契约，不是浏览器 API，不应直接暴露给前端：

| 调用方 | 方法与路径 | 请求 | 成功响应 | 失败 |
|---|---|---|---|---|
| backend 的 `RemoteMaterialPrototypeParser` | `POST /internal/file-parser/parse` | `multipart/form-data`：`file`、必填 `fileType`、可选 `topic`、可重复 `usageTypes` | `{ summary, keywords: string[], teachingStages: string[] }` | parser `422` `{ code, message }`；超限 `413` |
| parser service 健康检查 | `GET /internal/health` | 无 | `{ "status": "UP" }` | 服务不可用 |

backend 默认使用 `DeterministicMaterialPrototypeParser`（`a12.material-parser.mode=local`），不经过容器；设置为 `remote` 才由 `RemoteMaterialPrototypeParser` 调用上述地址。远程 parser 的 base URL、超时和 20 MiB 请求上限来自后端配置。后端自身没有公开的 generator HTTP endpoint：`GenerationService` 通过内部 `MockArtifactContentFactory.buildPpt/buildLessonPlan/buildInteraction` 生成 schema version 1 的 `PPT/DOCX/INTERACTION` JSON。AI revision 仍经 `/api/ai-workflow/revision` 和 `AIWorkflowGateway`，默认 Mock，不代表真实模型。

### 4.7 需求对话消息

对话接口是教师项目工作流的持久化消息接口，不会直接调用 AI。所有路径受 `/api/**` 的 `TEACHER` 规则保护，并要求当前教师是未软删除项目的 owner；跨教师、leader、student 或已软删除项目分别按 `403`/`404` 拒绝。

| 方法与路径 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|
| `POST /api/projects/{projectId}/dialogues` | `{ sessionId, sender, content, roundNo }`；四项非空，`roundNo >= 1` | `DialogMessageResponse`：`id/projectId/sessionId/sender/content/roundNo/createdAt` | `400` 缺字段、非法 sender/round，`403`/`404` |
| `GET /api/projects/{projectId}/dialogues` | 无 | 按 `createdAt`、`id` 升序的 `DialogMessageResponse[]` | `403`/`404` |
| `GET /api/dialogues/{sessionId}` | 无；sessionId 非空 | 该 session 的 `DialogMessageResponse[]`；涉及多个项目时必须对每个项目都有 owner 权限 | `400` sessionId 为空，`403`/`404` |
| `DELETE /api/projects/{projectId}/dialogues` | 无 | `DialogClearResponse`：`projectId/deletedCount` | `403`/`404` |

`sender` 请求枚举为 `TEACHER`、`AI`、`ASSISTANT`、`SYSTEM`；服务端把 `ASSISTANT` 响应归一为 `AI`。前端清空接口的真实响应是对象并读取 `data.deletedCount`，不是数字；现有 `frontend/src/api/dialogues.ts` 的 `ApiResponse<number>` 类型与后端不一致，重构时需修正。对话记录不是版本成果，清空只删除消息，不影响需求、版本或审批。

## 5. 成果生成、版本查询与定稿

### 5.1 生成前置接口

角色为 owner `TEACHER`，路径仍是 `/api/projects/{projectId}/...`。

| 方法与路径 | 请求体 | 成功 `data` | 主要 4xx |
|---|---|---|---|
| `POST /api/projects/{projectId}/generation-plans` | 无 | `GenerationPlanResponse`：`id/projectId/provider/pptOutline/docOutline/interactionPlan/confirmed` | `409` 未确认教学意图或前置流程不足；`403`/`404` |
| `GET /api/projects/{projectId}/generation-plans/latest` | 无 | 最新 plan；没有时当前服务返回 `404` | `403`/`404` |
| `PUT /api/projects/{projectId}/generation-plans/{planId}` | `{ pptOutline: [{ order, title, description }], docOutline: [{ order, title, description }], interactionPlan: [string] }`；三者均非空 | 更新后的 plan | `400` 空/非法 section，`409` 已确认 plan 不可改，`403`/`404` |
| `POST /api/projects/{projectId}/generation-plans/{planId}/confirm` | 无 | `confirmed: true` | `400`/`409` 前置或状态问题，`403`/`404` |
| `POST /api/projects/{projectId}/artifacts/generate` | `{ planId }` | `ArtifactResponse[]`：`id/versionId/versionNumber/type/title/schemaVersion/content/createdAt` | `400` planId 非法，`409` plan 未确认或已有其他 plan 成果，`403`/`404` |
| `GET /api/projects/{projectId}/artifacts` | 无 | `ArtifactResponse[]` | `403`/`404` |
| `GET /api/projects/{projectId}/artifacts/{artifactId}` | 无 | 单个 `ArtifactResponse` | `403`/`404` |
| `GET /api/projects/{projectId}/generation/workspace` | 无 | `{ projectId, projectName, projectStatus, provider, teachingIntent, latestPlan, artifacts, capabilities }` | `403`/`404` |

成果类型：`PPT`、`DOCX`、`INTERACTION`。生成产生的 `ArtifactVersion` 才能进入下一节版本接口。

### 5.2 版本接口

角色严格为项目 owner `TEACHER`；其他教师、leader、student 均拒绝。前缀 `/api/v1/projects/{projectId}/artifact-versions`。

| 方法与路径 | 请求体 | 成功 `data` 关键字段 | 主要 4xx/约束 |
|---|---|---|---|
| `GET /api/v1/projects/{projectId}/artifact-versions` | 无 | `ArtifactVersionResponse[]`：`id/projectId/generationPlanId/versionNumber/description/finalVersion/artifactCount/createdAt` | `400` projectId 非正数，`403` 非 owner/非 teacher，`404` project 不存在 |
| `PUT /api/v1/projects/{projectId}/artifact-versions/{versionId}/finalize` | 无 | 被定稿版本 DTO；`finalVersion: true` | `400` path 参数非法，`403`，`404` 版本不属于 project，`409` 版本没有任何生成成果 |

定稿只切换 `finalVersion`，不修改描述或成果；重复定稿幂等。版本查询只返回至少有生成成果的版本，前端应使用 `artifactCount` 和 `finalVersion` 标识“可定稿/已定稿”。

### 5.3 成果修订与修改记录

仅项目 owner `TEACHER` 可修订或查询该项目修改记录；其他教师、`LEADER`、`STUDENT` 返回 `403`。修订的 source artifact 必须属于 path 中的 project 和 source version；project/artifact 不匹配返回 `404`。source version `finalVersion=true` 时明确返回 `409`：`Final artifact version cannot be revised`。

| 方法与路径 | 请求体 | 成功 `data` 关键字段 | 主要 4xx/状态 |
|---|---|---|---|
| `POST /api/v1/projects/{projectId}/artifacts/{artifactId}/revisions` | `{ "instruction": string }`；非空，最多 4000 字符 | `RevisionResponse`：`version`（新版本元数据且 `finalVersion=false`）、`artifacts[]`（完整克隆后的成果）、`changeSummary`、`changedSections`、`requestedProvider/activeProvider/mockProvider/providerMessage`、`editRecord` | `400` 空或超长 instruction/path 非法；`403` 非 owner teacher；`404` 项目、成果或版本不匹配；`409` source 已定稿、source 无成果或 JSON/schema 不可修订；AI 不可用时 `503` |
| `GET /api/v1/projects/{projectId}/edit-records` | 无 | `EditRecordResponse[]`：`id/projectId/versionId/instruction/resultSummary/createdAt`，按创建时间倒序 | `400` path 非法；`403` 非 owner teacher；`404` project |

修订行为：版本号取 project 当前最大版本号加一；复制 source version 全部成果，只对目标类型做结构化追加。PPT 追加 revision slide，DOCX 追加 revision section，INTERACTION 追加合规 question；其他成果内容 JSON 原样克隆。源版本实体及其成果不会被更新，新版本永不自动定稿。`ArtifactResponse` 的 `content` 是 JSON 节点，不能按实体字段读取。

前端联调：提交前展示 `activeProvider`；`mockProvider=true` 时明确标注 Mock，不显示为真实 Dify/模型结果。成功后用返回的 `version` 和 `artifacts` 刷新预览；`409` 应保留 instruction 并提示“定稿版本不可修改”，不要重试同一个 source。

### 前端联调注意事项

- 审批提交必须先通过版本查询选择版本，再调用定稿接口；不要让用户手填 `artifactVersionId`。
- 只有 `finalVersion: true` 才能提交审批；定稿按钮应防重复点击并处理 `409` 无成果。
- 版本 DTO 不包含成果内容；需要预览时调用 artifacts 接口，不要从版本 DTO 猜字段。

## 6. 审批提交、查询、审核与撤回

接口前缀：`/api/v1/approval-requests`。

### 角色权限

- 提交：项目 owner `TEACHER`；只能提交自己的已定稿版本，reviewer 必须是启用的 `LEADER`，且不能是提交教师本人。
- 查询/详情：教师只能看到自己提交的，leader 只能看到分配给自己的。
- 审核：只能由被分配的 leader 进行。
- 撤回：只能由提交教师在 `SUBMITTED` 状态撤回。

### 接口

| 方法与路径 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|
| `POST /api/v1/approval-requests` | `{ projectId, artifactVersionId, reviewerId }`，均为正整数 | `ApprovalRequestResponse`：`id/artifactVersionId/artifactVersionNumber/projectId/projectName/submittedBy/reviewerId/status/reviewNote/submittedAt` | `400` 版本不属于项目、版本未定稿、reviewer 不是启用 leader/本人，`403` 非 owner，`404` project/version/reviewer，`409` 该版本已有 active approval |
| `GET /api/v1/approval-requests?status={status}` | status 可选 | 当前 teacher 的提交列表或当前 leader 的分配列表 | `400` 非法枚举，`403` |
| `GET /api/v1/approval-requests/{approvalRequestId}` | 无 | 单个 `ApprovalRequestResponse` | `403` 非提交人/非 reviewer，`404` |
| `PUT /api/v1/approval-requests/{approvalRequestId}/review` | `{ status: "APPROVED" | "REVISION_REQUIRED", note? }`；退回时 note 必填，最多 5000 | 更新后的响应；审核后 `reviewedAt`，active version 解除占用 | `400` 非法审核状态/退回无 note，`403` 非 reviewer，`404`，`409` 非 `SUBMITTED` |
| `POST /api/v1/approval-requests/{approvalRequestId}/cancel` | 无 | `status: "CANCELLED"`，active version 解除占用 | `403` 非提交人，`404`，`409` 非 `SUBMITTED` |

状态枚举：`SUBMITTED`、`APPROVED`、`REVISION_REQUIRED`、`CANCELLED`。审批响应中的 `artifactVersionNumber` 可能为空，不要只依赖它拼接版本标题。

### 前端联调注意事项

- status 查询使用服务端 enum 大写值；切换筛选后重新请求，不要在前端把其他角色的列表拼到一起。
- 审核成功后更新行数据；在 `status` 过滤视图中，状态改变的行可能从当前列表消失。
- “撤回”不是删除，保留申请 id 和 `CANCELLED` 状态；同一申请只能撤回一次。

## 7. 发布、撤回与学生学习任务

### 角色与状态

- 发布和发布列表/详情：leader 负责发布；leader 只看到自己发布的，teacher 只看到自己 owner project 的发布记录。
- 撤回：只能是发布该记录的 leader。
- 学生学习任务：只对 `STUDENT` 开放，且必须有对应 `class_memberships`；只返回 `PUBLISHED`。
- 发布状态：`PUBLISHED`、`WITHDRAWN`。

### 接口

| 方法与路径 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|
| `POST /api/v1/publications` | `{ approvalRequestId, classId, title, summary? }`；title 最多 200，summary 最多 5000 | `PublicationResponse`：`id/approvalRequestId/artifactVersionId/projectId/projectName/courseId/classId/title/publishedBy/status/publishedAt` | `400` class course 与 project course 不匹配，`403` 非 reviewer leader，`404` approval/class/course/version/project，`409` approval 未 APPROVED 或同申请同班级已有发布 |
| `GET /api/v1/publications?status={status}` | status 可选 | 当前 leader/teacher 可见的 `PublicationResponse[]` | `400` 非法 enum，`403` |
| `GET /api/v1/publications/{publicationId}` | 无 | 单个 `PublicationResponse` | `403` 不在 leader/teacher 可见范围，`404` |
| `POST /api/v1/publications/{publicationId}/withdraw` | 无 | `status: "WITHDRAWN"`、`withdrawnAt` | `403` 非发布 leader，`404`，`409` 非 `PUBLISHED` |
| `GET /api/v1/student/learning-tasks` | `STUDENT` | `LearningTaskSummary[]`：`publicationId/approvalRequestId/artifactVersionId/projectId/courseId/classId/title/summary/publishedAt` | `401`/`403` |
| `GET /api/v1/student/learning-tasks/{publicationId}` | `STUDENT` 且已加入该班级 | `LearningTaskDetail`，额外含 `artifactVersion` 元数据和 `artifacts[]` | `403` 未加入班级，`404` 不存在或未发布 |

`LearningTaskDetail.artifacts[]` 字段为 `artifactType/title/contentJson/schemaVersion`。学生端应把 publication id 当作学习任务资源 id；它不是 artifactVersionId。

### 前端联调注意事项

- 发布前必须保证 approval 是 `APPROVED`，并选择与 project course 同名的 class；服务端不会自动替换班级。
- 撤回后学生列表不再返回该任务；详情也按未发布处理。
- 成员关系没有 HTTP 管理接口，学生看不到任务通常应先检查后端种子/数据层的 membership，而不是重试发布接口。

## 8. 问答：创建、查询、回答与状态流转

接口前缀：`/api/v1/questions`。

### 角色权限

- 创建：已加入发布班级的 `STUDENT`。
- 查询/详情：student 只能看自己的问题；teacher 只能看自己 owner project 的问题；leader 只能看自己发布范围的问题。
- 回答：project owner `TEACHER`；leader 和 student 不能回答。
- 状态更新：问题 student 或 project owner teacher；leader 没有状态更新接口权限。

### 接口

| 方法与路径 | 请求体/参数 | 成功 `data` 关键字段 | 主要 4xx |
|---|---|---|---|
| `POST /api/v1/questions` | `{ publicationId, title, content }`；title 最多 200，content 最多 5000 | `QuestionResponse`：`id/publicationId/projectId/studentId/title/content/status/answers` | `400` 校验，`403` 未加入班级，`404` publication，`409` publication 未发布 |
| `GET /api/v1/questions?publicationId={id}&status={status}` | 两个 query 都可选 | 按当前角色范围返回 `QuestionResponse[]` | `400` 非法 status，`403` |
| `GET /api/v1/questions/{questionId}` | 无 | 问题详情及 `answers[]`：`id/questionId/teacherId/teacherName/content/createdAt` | `403` 越权，`404` |
| `POST /api/v1/questions/{questionId}/answers` | `{ content }`，最多 5000 | 问题变为 `ANSWERED`，含新增回答和 `answeredAt` | `403` 非 owner teacher，`404`，`409` 问题已关闭 |
| `PUT /api/v1/questions/{questionId}/status` | `{ status: "OPEN" | "ANSWERED" | "CLOSED" }` | 更新后的 `status/answeredAt/closedAt` | `400` 非法/重复/不允许流转，`403`，`404` |

状态流转：初始 `OPEN`；`OPEN -> CLOSED`；`ANSWERED -> OPEN` 或 `CLOSED`；已 `CLOSED` 不可重开。回答接口会把问题设为 `ANSWERED`。状态字符串大小写不敏感后由服务端规范化，但前端应发送大写枚举。

## 9. 真实导出

接口前缀：`/api/v1/projects/{projectId}/exports`。

### 角色权限

当前仅 `TEACHER` 可调用；project owner 可以导出，非 owner teacher 只有在自己作为教学任务 assignee 且该任务 `linkedProjectId` 指向 project 时才允许。leader/student 不允许。

### 接口

| 方法与路径 | 请求体 | 成功响应 | 主要 4xx |
|---|---|---|---|
| `GET /api/v1/projects/{projectId}/exports` | 无 | JSON `ApiResponse<ExportCatalog>`：`{ projectId, projectName, formats: [{ format, label, description, mediaType, extension, artifactId, versionId, versionNumber, filename, downloadUrl }] }` | `400` projectId 非法，`403` 非授权 teacher/无任务分配，`404` project |
| `GET /api/v1/projects/{projectId}/exports/pptx` | 无 | 二进制 PPTX；`Content-Type` 为 Office PPTX media type，带 `Content-Disposition`、`Content-Length`、`Cache-Control: no-store` | `400`/`403`/`404`；无可用 artifact 时 `404` |
| `GET /api/v1/projects/{projectId}/exports/docx` | 无 | 二进制 DOCX；同上 | 同上 |

当前真实支持的格式只有 `PPTX`、`DOCX`。虽然 `ExportType` 枚举中存在 `INTERACTION`、`PACKAGE`，导出 service 的 supported formats 不包含它们；不要在前端展示或调用对应路径。

导出会从 project 中选择每种格式版本号最高的可用成果，并写入 export record；导出接口不是下载已存文件，而是服务端即时渲染。

## 10. 辅助工作台聚合接口

这些接口不是单项资源 CRUD，但当前前端工作区已实际使用，路径均为 `/api`：

| 方法与路径 | 角色/请求 | 成功 `data` |
|---|---|---|
| `GET /api/health` | 公开 | `{ status, service, version, timestamp }` |
| `GET /api/workspace/overview` | 已认证教师工作区 | `TeacherWorkspaceResponse`，含 metrics、continueProjects、pendingTasks、recentActivities、suggestions |
| `GET /api/workspace/projects?query=&stage=&page=0&size=10&sort=UPDATED_DESC` | owner teacher；size 1-100 | `ProjectPageResponse`：`items/page/size/totalElements/totalPages/sort/query/stage` |
| `GET /api/projects/{projectId}/workspace-overview` | owner teacher | 项目阶段、metrics、timeline、quickActions |
| `GET /api/projects/{projectId}/requirements/workspace` | owner teacher | 需求工作区聚合 |
| `GET /api/projects/{projectId}/requirement-summaries/workspace` | owner teacher | 摘要工作区聚合 |
| `GET /api/projects/{projectId}/materials/workspace` | owner teacher | 资料、解析、索引聚合 |
| `POST /api/projects/{projectId}/knowledge/workspace-search` | owner teacher；body 含 query/limit 等当前 DTO 字段 | workspace search response |
| `GET /api/projects/{projectId}/teaching-intents/workspace` | owner teacher | 意图工作区聚合 |
| `PUT /api/projects/{projectId}/teaching-intents/{intentId}/workspace` | owner teacher；当前 DTO 字段 | 更新后的意图工作区 |

## 11. 当前真实缺口

以下不是前端遗漏，而是当前源码没有实现的能力，文档不提供伪路径：

1. 当前没有班级成员关系的批量导入接口；单个班级成员的查询、添加、移除已经由 `/api/v1/classes/{classId}/members` 提供，具体权限以第 2.2 节为准。
2. Dify provider 路由和配置字段存在，但真实 Dify workflow 未实现；当前默认是 Mock，关闭 fallback 时 AI workflow 返回 `503`。
3. 真实导出只支持 PPTX/DOCX；`INTERACTION`、`PACKAGE` 尚未实现。
4. 版本接口只返回版本元数据与成果数量，不直接返回实体或成果内容；成果预览需调用 artifacts 接口。
5. API 前缀尚未统一：认证、课程、教学任务、审批、发布、问答、版本、修订、导出使用 `/api/v1`，项目/需求/资料/知识/意图/生成使用 `/api` 或 `/api/projects`。前端 API 层应集中维护路径，不要自行补 `/v1`。
